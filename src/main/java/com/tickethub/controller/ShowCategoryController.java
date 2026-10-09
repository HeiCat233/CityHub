package com.tickethub.controller;

import com.tickethub.dto.Result;
import com.tickethub.service.IShowCategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 演出分类接口
 */
@RestController
@RequestMapping("/show-category")
public class ShowCategoryController {

    @Resource
    private IShowCategoryService categoryService;

    /** 分类列表（Redis List 缓存） */
    @GetMapping("list")
    public Result queryCategoryList() {
        return categoryService.querySort();
    }
}
