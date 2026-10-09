package com.tickethub.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tickethub.dto.Result;
import com.tickethub.entity.Show;
import com.tickethub.mapper.ShowMapper;
import com.tickethub.service.IShowService;
import com.tickethub.utils.CacheClient;
import com.tickethub.utils.RedisConstants;
import com.tickethub.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 演出服务实现
 *
 * <p>核心：演出详情用「逻辑过期 + 互斥锁」解决缓存击穿；附近演出用 Redis GEO。
 */
@Service
public class ShowServiceImpl extends ServiceImpl<ShowMapper, Show> implements IShowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 逻辑过期方案解决缓存击穿（缓存需先预热，见 ShowCacheTest）
        Show show = cacheClient.queryWithLogicalExpire(
                RedisConstants.CACHE_SHOW_KEY, id, Show.class,
                this::getById, RedisConstants.CACHE_SHOW_TTL, TimeUnit.MINUTES);
        if (show == null) {
            return Result.fail("演出不存在！");
        }
        return Result.ok(show);
    }

    @Override
    @Transactional
    public Result update(Show show) {
        Long id = show.getId();
        if (id == null) {
            return Result.fail("演出id不能为空");
        }
        // 先更新数据库
        updateById(show);
        // 再删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOW_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShowByType(Integer categoryId, Integer current, Double x, Double y) {
        // 不带坐标：直接查数据库分页
        if (x == null || y == null) {
            Page<Show> page = query()
                    .eq("category_id", categoryId)
                    .orderByAsc("sale_begin_time")
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        // 带坐标：走 Redis GEO 查 5km 内的演出，按距离排序
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = RedisConstants.SHOW_GEO_KEY + categoryId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            String showId = result.getContent().getName();
            ids.add(Long.valueOf(showId));
            distanceMap.put(showId, result.getDistance());
        });
        String idStr = StrUtil.join(",", ids);
        List<Show> shows = query()
                .in("id", ids).last("order by field(id," + idStr + ")").list();
        for (Show show : shows) {
            show.setDistance(distanceMap.get(show.getId().toString()).getValue());
        }
        return Result.ok(shows);
    }

    @Override
    public Result queryShowByName(String keyword, Integer current) {
        Page<Show> page = query()
                .like(StrUtil.isNotBlank(keyword), "name", keyword)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }
}
