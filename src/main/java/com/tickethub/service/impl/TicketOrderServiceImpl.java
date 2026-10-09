package com.tickethub.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tickethub.dto.Result;
import com.tickethub.dto.UserDTO;
import com.tickethub.entity.TicketOrder;
import com.tickethub.entity.TierStock;
import com.tickethub.mapper.TicketOrderMapper;
import com.tickethub.service.ITicketOrderService;
import com.tickethub.service.ITierStockService;
import com.tickethub.utils.RedisConstants;
import com.tickethub.utils.RedisIdWorker;
import com.tickethub.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 购票订单服务实现
 *
 * <p>抢票核心链路：
 * <ol>
 *   <li>Lua 脚本在 Redis 中原子完成「库存校验 + 一人一单校验 + 扣减库存 + 记录用户」；</li>
 *   <li>校验通过后把订单投递到 RabbitMQ，立即返回订单号（异步出票）；</li>
 *   <li>消费者落库并扣减数据库库存，用 stock &gt; 0 乐观锁兜底防超卖。</li>
 * </ol>
 */
@Slf4j
@Service
public class TicketOrderServiceImpl extends ServiceImpl<TicketOrderMapper, TicketOrder> implements ITicketOrderService {

    @Resource
    private ITierStockService tierStockService;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    /** 抢票 Lua 脚本 */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillTicket(Long tierId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();
        // 生成全局唯一订单号
        long orderId = redisIdWorker.nextId("order");

        // 1. 执行 Lua：原子判断库存 + 一人一单 + 扣减
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                tierId.toString(), userId.toString(), String.valueOf(orderId));

        int r = result == null ? -1 : result.intValue();
        if (r != 0) {
            if (r == 1) {
                return Result.fail("票已售罄");
            }
            if (r == 2) {
                return Result.fail("每人限购一张，请勿重复抢票");
            }
            return Result.fail("该票档未开票或已结束");
        }

        // 2. 组装订单消息投递 MQ（异步出票）
        TicketOrder order = new TicketOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setTierId(tierId);
        TierStock stock = tierStockService.getById(tierId);
        if (stock != null) {
            order.setShowId(stock.getShowId());
        }
        try {
            rabbitTemplate.convertAndSend("X", "XA", JSONUtil.toJsonStr(order));
        } catch (Exception e) {
            log.error("投递抢票消息失败, orderId={}", orderId, e);
            // 投递失败要把 Redis 库存和已购标记回滚，否则名额被白白占用
            stringRedisTemplate.opsForValue().increment(RedisConstants.SECKILL_STOCK_KEY + tierId);
            stringRedisTemplate.opsForSet().remove(RedisConstants.SECKILL_ORDER_KEY + tierId, userId.toString());
            return Result.fail("系统繁忙，请稍后重试");
        }
        // 3. 返回订单号，前端凭订单号轮询/查询订单状态
        return Result.ok(orderId);
    }

    @Override
    @Transactional
    public void createTicketOrder(TicketOrder order) {
        // 1. 幂等：同一订单号只落库一次
        if (getById(order.getId()) != null) {
            log.warn("订单已存在，跳过重复消费, orderId={}", order.getId());
            return;
        }
        // 2. 补全订单信息
        TierStock stock = tierStockService.getById(order.getTierId());
        if (stock != null) {
            order.setShowId(stock.getShowId());
        }
        order.setQuantity(1);
        order.setStatus(1); // 待支付
        order.setPayType(0);
        order.setCreateTime(LocalDateTime.now());
        // 3. 扣减数据库库存（乐观锁：stock > 0）
        boolean success = tierStockService.update()
                .setSql("stock = stock - 1")
                .eq("tier_id", order.getTierId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("数据库库存不足，出票失败, tierId={}", order.getTierId());
            return;
        }
        // 4. 保存订单
        save(order);
    }

    @Override
    public Result queryMyOrders(Integer current) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        List<TicketOrder> orders = query()
                .eq("user_id", user.getId())
                .orderByDesc("create_time")
                .list();
        return Result.ok(orders);
    }

    @Override
    @Transactional
    public Result payOrder(Long orderId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        TicketOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (!order.getUserId().equals(user.getId())) {
            return Result.fail("无权操作该订单");
        }
        // 乐观锁：仅当状态仍为「待支付」时才更新，避免与取消订单并发冲突
        boolean success = update()
                .set("status", 2)
                .set("pay_type", 2)
                .set("pay_time", LocalDateTime.now())
                .eq("id", orderId)
                .eq("status", 1)
                .update();
        if (!success) {
            return Result.fail("订单状态已变更，无法支付");
        }
        return Result.ok();
    }
}
