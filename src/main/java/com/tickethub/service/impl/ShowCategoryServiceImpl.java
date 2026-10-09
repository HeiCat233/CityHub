package com.tickethub.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tickethub.dto.Result;
import com.tickethub.entity.ShowCategory;
import com.tickethub.mapper.ShowCategoryMapper;
import com.tickethub.service.IShowCategoryService;
import com.tickethub.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 演出分类服务实现（Redis List 缓存）
 */
@Service
public class ShowCategoryServiceImpl extends ServiceImpl<ShowCategoryMapper, ShowCategory> implements IShowCategoryService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result querySort() {
        String key = RedisConstants.SHOW_CATEGORY_KEY;
        // 1. 先查 Redis 缓存
        List<String> cache = stringRedisTemplate.opsForList().range(key, 0, -1);
        if (cache != null && !cache.isEmpty()) {
            List<ShowCategory> categories = cache.stream()
                    .map(s -> JSONUtil.toBean(s, ShowCategory.class))
                    .collect(java.util.stream.Collectors.toList());
            return Result.ok(categories);
        }
        // 2. 缓存未命中，查数据库
        List<ShowCategory> list = query().orderByAsc("sort").list();
        if (list == null || list.isEmpty()) {
            return Result.ok(java.util.Collections.emptyList());
        }
        // 3. 写入 Redis List 缓存
        list.forEach(c -> stringRedisTemplate.opsForList()
                .rightPush(key, JSONUtil.toJsonStr(c)));
        stringRedisTemplate.expire(key, RedisConstants.SHOW_CATEGORY_TTL, TimeUnit.MINUTES);
        return Result.ok(list);
    }
}
