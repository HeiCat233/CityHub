package com.tickethub;

import com.tickethub.entity.Show;
import com.tickethub.service.IShowService;
import com.tickethub.utils.CacheClient;
import com.tickethub.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 缓存预热：把演出详情写入 Redis（逻辑过期格式）与 GEO 索引
 *
 * <p>逻辑过期方案要求缓存必须存在，因此每次清空 Redis 或重启后需要先执行本测试。
 */
@SpringBootTest
class ShowCacheTest {

    @Resource
    private IShowService showService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void testSaveShowCache() {
        List<Show> shows = showService.list();
        for (Show show : shows) {
            // 1. 演出详情写入逻辑过期缓存
            cacheClient.setWithLogicalExpire(
                    RedisConstants.CACHE_SHOW_KEY + show.getId(),
                    show, RedisConstants.CACHE_SHOW_TTL, TimeUnit.MINUTES);
        }
        System.out.println("演出详情缓存预热完成，共 " + shows.size() + " 条");
    }

    @Test
    void testSaveShowGeo() {
        // 按分类把演出坐标写入 GEO，供"附近演出"查询
        List<Show> shows = showService.list();
        shows.stream()
                .collect(Collectors.groupingBy(Show::getCategoryId))
                .forEach((categoryId, list) -> {
                    String key = RedisConstants.SHOW_GEO_KEY + categoryId;
                    list.forEach(show -> stringRedisTemplate.opsForGeo()
                            .add(key, new Point(show.getX(), show.getY()), show.getId().toString()));
                });
        System.out.println("演出 GEO 索引预热完成");
    }
}
