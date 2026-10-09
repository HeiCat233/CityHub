package com.tickethub.config;

import com.tickethub.entity.TierStock;
import com.tickethub.service.ITierStockService;
import com.tickethub.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.List;

/**
 * 秒杀库存预热
 *
 * <p>抢票 Lua 脚本从 Redis 读取库存，因此应用启动时把数据库库存同步到 Redis。
 * 采用「Redis 不存在才写入」的策略，避免重启应用把已经抢掉的库存覆盖回原值。
 */
@Slf4j
@Configuration
public class SeckillStockPreloader {

    @Resource
    private ITierStockService tierStockService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Bean
    public ApplicationRunner seckillStockRunner() {
        return args -> {
            List<TierStock> stocks = tierStockService.list();
            int loaded = 0;
            for (TierStock stock : stocks) {
                String key = RedisConstants.SECKILL_STOCK_KEY + stock.getTierId();
                // 仅在 Redis 中不存在时写入，保留运行期间的扣减结果
                Boolean absent = stringRedisTemplate.opsForValue()
                        .setIfAbsent(key, String.valueOf(stock.getStock()));
                if (Boolean.TRUE.equals(absent)) {
                    loaded++;
                }
            }
            log.info("秒杀库存预热完成，本次写入 {} 个票档", loaded);
        };
    }
}
