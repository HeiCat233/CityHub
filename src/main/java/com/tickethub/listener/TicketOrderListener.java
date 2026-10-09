package com.tickethub.listener;

import cn.hutool.json.JSONUtil;
import com.tickethub.entity.TicketOrder;
import com.tickethub.service.ITicketOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 抢票订单 MQ 消费者
 *
 * <p>正常队列 QA 消费抢票消息并异步出票；若消息在 QA 中滞留超过 TTL，
 * 会进入死信队列 QD 由 receivedD 兜底处理。两个消费者都走
 * {@link ITicketOrderService#createTicketOrder}，该方法内部按订单号幂等，
 * 因此重复投递不会产生重复订单（修复了原项目死信重复落库的缺陷）。
 */
@Slf4j
@Component
public class TicketOrderListener {

    @Resource
    private ITicketOrderService ticketOrderService;

    /**
     * 正常队列消费者：异步出票
     */
    @RabbitListener(queues = "QA")
    public void receivedA(Message message) {
        String msg = new String(message.getBody());
        log.info("正常队列收到抢票消息: {}", msg);
        TicketOrder order = JSONUtil.toBean(msg, TicketOrder.class);
        // 幂等 + 乐观锁扣库存，实现在 service 内
        ticketOrderService.createTicketOrder(order);
    }

    /**
     * 死信队列消费者：兜底处理超时消息
     */
    @RabbitListener(queues = "QD")
    public void receivedD(Message message) {
        String msg = new String(message.getBody());
        log.warn("死信队列兜底处理抢票消息: {}", msg);
        TicketOrder order = JSONUtil.toBean(msg, TicketOrder.class);
        ticketOrderService.createTicketOrder(order);
    }
}
