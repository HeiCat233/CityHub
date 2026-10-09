package com.tickethub.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tickethub.dto.Result;
import com.tickethub.entity.ShowCategory;

/**
 * 演出分类服务
 */
public interface IShowCategoryService extends IService<ShowCategory> {

    /** 查询分类列表（Redis List 缓存） */
    Result querySort();
}
