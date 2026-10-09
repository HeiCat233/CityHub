package com.tickethub.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tickethub.dto.Result;
import com.tickethub.entity.TicketTier;
import com.tickethub.entity.TierStock;
import com.tickethub.mapper.TicketTierMapper;
import com.tickethub.service.ITicketTierService;
import com.tickethub.service.ITierStockService;
import com.tickethub.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 票档服务实现
 *
 * <p>新增 type=2 的秒杀票档时，同步创建库存记录；查询票档列表时
 * 把库存/开票时间一并返回给前端，并做「开票即预热 Redis 库存」。
 */
@Service
public class TicketTierServiceImpl extends ServiceImpl<TicketTierMapper, TicketTier> implements ITicketTierService {

    @Resource
    private ITierStockService tierStockService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional
    public void addTicketTier(TicketTier tier) {
        // 1. 保存票档
        save(tier);
        // 2. 秒杀票档需要创建库存记录
        if (tier.getType() != null && tier.getType() == 2) {
            TierStock stock = new TierStock();
            stock.setTierId(tier.getId());
            stock.setShowId(tier.getShowId());
            stock.setStock(tier.getStock() == null ? 0 : tier.getStock());
            stock.setBeginTime(tier.getBeginTime() == null ? LocalDateTime.now() : tier.getBeginTime());
            stock.setEndTime(tier.getEndTime() == null ? LocalDateTime.now().plusDays(1) : tier.getEndTime());
            tierStockService.save(stock);
            // 3. 预热 Redis 库存，供 Lua 秒杀脚本判断
            stringRedisTemplate.opsForValue()
                    .set(RedisConstants.SECKILL_STOCK_KEY + tier.getId(), String.valueOf(stock.getStock()));
        }
    }

    @Override
    public Result queryTierOfShow(Long showId) {
        // 1. 查票档
        List<TicketTier> tiers = query().eq("show_id", showId).list();
        if (tiers == null || tiers.isEmpty()) {
            return Result.ok(new ArrayList<>());
        }
        // 2. 批量查库存
        List<Long> tierIds = new ArrayList<>(tiers.size());
        tiers.forEach(t -> tierIds.add(t.getId()));
        List<TierStock> stocks = tierStockService.query().in("tier_id", tierIds).list();
        Map<Long, TierStock> stockMap = new HashMap<>(stocks.size());
        stocks.forEach(s -> stockMap.put(s.getTierId(), s));
        // 3. 组装返回（用 Map 承载库存与开票时间，避免实体冗余字段）
        List<Map<String, Object>> result = new ArrayList<>(tiers.size());
        for (TicketTier tier : tiers) {
            Map<String, Object> item = new HashMap<>(8);
            item.put("id", tier.getId());
            item.put("showId", tier.getShowId());
            item.put("name", tier.getName());
            item.put("price", tier.getPrice());
            item.put("rules", tier.getRules());
            item.put("type", tier.getType());
            TierStock stock = stockMap.get(tier.getId());
            if (stock != null) {
                // 库存以 Redis 为准（Redis 是秒杀实时库存）
                String redisStock = stringRedisTemplate.opsForValue()
                        .get(RedisConstants.SECKILL_STOCK_KEY + tier.getId());
                item.put("stock", redisStock == null ? stock.getStock() : Integer.valueOf(redisStock));
                item.put("beginTime", stock.getBeginTime());
                item.put("endTime", stock.getEndTime());
            }
            result.add(item);
        }
        return Result.ok(result);
    }
}
