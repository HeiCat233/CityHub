package com.tickethub.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 缓存工具：缓存穿透（空值缓存）与缓存击穿（逻辑过期 + 互斥锁重建）
 *
 * <p>两种方案的取舍见文档第 2.3 节：热点数据可容忍短暂不一致，因此演出详情
 * 采用逻辑过期（AP），查不到数据时用空值缓存兜住穿透（CP）。
 */
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** 普通缓存写入（带 TTL） */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /** 逻辑过期写入：不设物理 TTL，把过期时间包在 value 里 */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透方案：查不到时缓存空值
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 空字符串代表缓存了空值，直接返回 null 挡住穿透
        if (json != null) {
            return null;
        }
        R r = dbFallback.apply(id);
        if (r == null) {
            stringRedisTemplate.opsForValue()
                    .set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.set(key, r, time, unit);
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 缓存击穿方案：逻辑过期 + 互斥锁 + 异步重建
     *
     * <p>注意：逻辑过期方案依赖「缓存已预热」。但生产环境第一次部署、
     * 或者手动清过 Redis 之后，缓存里什么都没有，如果这里直接返回 null，
     * 详情页就会一直报「演出不存在」。所以未命中时必须回源数据库并写入缓存，
     * 否则这套方案在冷启动阶段是不可用的。
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 空字符串是「空值缓存」标记（防止缓存穿透），直接返回 null，不回源
        if (json != null && StrUtil.isBlank(json)) {
            return null;
        }
        if (json == null) {
            // 真正的缓存未命中（冷启动 / 清过 Redis）。
            // 这里也要抢锁：否则首屏并发进来会同时回源数据库（等于击穿）。
            String lockKey = RedisConstants.LOCK_SHOW_KEY + id;
            if (tryLock(lockKey)) {
                try {
                    // 双检：抢到锁之前可能已经有别的线程写好缓存了
                    String again = stringRedisTemplate.opsForValue().get(key);
                    if (again != null) {
                        return StrUtil.isBlank(again) ? null : parse(again, type);
                    }
                    R fresh = dbFallback.apply(id);
                    if (fresh == null) {
                        // 数据库也没有：写空值缓存，挡住后续穿透
                        stringRedisTemplate.opsForValue()
                                .set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                        return null;
                    }
                    this.setWithLogicalExpire(key, fresh, time, unit);
                    return fresh;
                } finally {
                    unLock(lockKey);
                }
            }
            // 没抢到锁：短暂等一下让持锁线程写缓存，再读一次；仍没有就直接回源（不回写）
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String afterWait = stringRedisTemplate.opsForValue().get(key);
            if (afterWait != null) {
                return StrUtil.isBlank(afterWait) ? null : parse(afterWait, type);
            }
            return dbFallback.apply(id);
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期，直接返回
            return r;
        }
        // 已过期：抢到锁的线程异步重建，其他线程先返回旧数据（不阻塞）
        String lockKey = RedisConstants.LOCK_SHOW_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R fresh = dbFallback.apply(id);
                    if (fresh == null) {
                        // 数据已被删除：写空值缓存，避免后续请求继续回源
                        stringRedisTemplate.opsForValue()
                                .set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                    } else {
                        this.setWithLogicalExpire(key, fresh, time, unit);
                    }
                } catch (Exception e) {
                    log.error("缓存重建失败, key={}", key, e);
                } finally {
                    // 注意：释放的是锁 key，不是缓存 key
                    unLock(lockKey);
                }
            });
        }
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    /** 把逻辑过期结构里的 data 解析成目标类型 */
    private <R> R parse(String json, Class<R> type) {
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        return JSONUtil.toBean((JSONObject) redisData.getData(), type);
    }
}
