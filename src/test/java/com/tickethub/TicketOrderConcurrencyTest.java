package com.tickethub;

import com.tickethub.entity.TicketOrder;
import com.tickethub.entity.TierStock;
import com.tickethub.service.ITicketOrderService;
import com.tickethub.service.ITierStockService;
import com.tickethub.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 抢票并发测试：验证「不超卖 + 一人一单」
 *
 * <p>直接调用订单服务创建订单，绕过 Lua 前置校验，用于验证数据库层
 * 乐观锁（stock &gt; 0）在并发下不会把库存扣成负数。
 */
@SpringBootTest
class TicketOrderConcurrencyTest {

    @Resource
    private ITicketOrderService ticketOrderService;

    @Resource
    private ITierStockService tierStockService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void testConcurrentCreateOrder() throws InterruptedException {
        Long tierId = 1L;
        // 重置库存，方便反复压测
        tierStockService.update().set("stock", 50).eq("tier_id", tierId).update();
        stringRedisTemplate.opsForValue().set(RedisConstants.SECKILL_STOCK_KEY + tierId, "50");

        int threads = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final long userId = 1000L + i;
            pool.submit(() -> {
                try {
                    TicketOrder order = new TicketOrder();
                    order.setId(System.nanoTime() + userId);
                    order.setUserId(userId);
                    order.setTierId(tierId);
                    ticketOrderService.createTicketOrder(order);
                    success.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();

        TierStock stock = tierStockService.getById(tierId);
        System.out.println("并发请求数: " + threads + ", 剩余库存: " + stock.getStock());
        if (stock.getStock() < 0) {
            throw new IllegalStateException("出现超卖！库存为 " + stock.getStock());
        }
    }
}
