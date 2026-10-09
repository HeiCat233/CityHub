package com.tickethub.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tickethub.dto.Result;
import com.tickethub.entity.TicketOrder;

/**
 * 购票订单服务
 */
public interface ITicketOrderService extends IService<TicketOrder> {

    /** 抢票下单（核心：Lua 资格判断 + MQ 异步出票） */
    Result seckillTicket(Long tierId);

    /** 创建订单（事务方法，由 MQ 消费者调用） */
    void createTicketOrder(TicketOrder order);

    /** 查询当前用户的订单列表 */
    Result queryMyOrders(Integer current);

    /** 支付订单 */
    Result payOrder(Long orderId);
}
