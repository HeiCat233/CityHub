package com.tickethub.controller;

import com.tickethub.dto.Result;
import com.tickethub.service.ITicketOrderService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * 购票订单接口
 */
@RestController
@RequestMapping("/ticket-order")
public class TicketOrderController {

    @Resource
    private ITicketOrderService ticketOrderService;

    /** 抢票（核心接口：Lua 资格判断 + MQ 异步出票） */
    @PostMapping("/seckill/{tierId}")
    public Result seckillTicket(@PathVariable("tierId") Long tierId) {
        return ticketOrderService.seckillTicket(tierId);
    }

    /** 我的订单列表 */
    @GetMapping("/my")
    public Result queryMyOrders(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return ticketOrderService.queryMyOrders(current);
    }

    /** 支付订单 */
    @PostMapping("/pay/{orderId}")
    public Result payOrder(@PathVariable("orderId") Long orderId) {
        return ticketOrderService.payOrder(orderId);
    }
}
