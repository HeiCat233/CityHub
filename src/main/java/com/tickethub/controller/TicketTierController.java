package com.tickethub.controller;

import com.tickethub.dto.Result;
import com.tickethub.entity.TicketTier;
import com.tickethub.service.ITicketTierService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * 票档接口
 */
@RestController
@RequestMapping("/tier")
public class TicketTierController {

    @Resource
    private ITicketTierService ticketTierService;

    /** 新增票档（type=2 为秒杀票档，会创建库存并预热 Redis） */
    @PostMapping
    public Result addTicketTier(@RequestBody TicketTier tier) {
        ticketTierService.addTicketTier(tier);
        return Result.ok(tier.getId());
    }

    /** 查询某演出的票档列表（含实时库存、开票时间） */
    @GetMapping("/list/{showId}")
    public Result queryTierOfShow(@PathVariable("showId") Long showId) {
        return ticketTierService.queryTierOfShow(showId);
    }
}
