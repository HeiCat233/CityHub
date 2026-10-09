package com.tickethub.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tickethub.dto.Result;
import com.tickethub.entity.TicketTier;

/**
 * 票档服务
 */
public interface ITicketTierService extends IService<TicketTier> {

    /** 新增票档（type=2 时同时创建库存记录） */
    void addTicketTier(TicketTier tier);

    /** 查询某演出的票档列表（含库存、开票时间） */
    Result queryTierOfShow(Long showId);
}
