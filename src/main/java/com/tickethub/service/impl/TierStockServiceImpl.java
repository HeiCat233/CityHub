package com.tickethub.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tickethub.entity.TierStock;
import com.tickethub.mapper.TierStockMapper;
import com.tickethub.service.ITierStockService;
import org.springframework.stereotype.Service;

/**
 * 票档库存服务实现
 */
@Service
public class TierStockServiceImpl extends ServiceImpl<TierStockMapper, TierStock> implements ITierStockService {
}
