package com.tickethub.controller;

import com.tickethub.dto.Result;
import com.tickethub.entity.Show;
import com.tickethub.service.IShowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * 演出接口
 */
@RestController
@RequestMapping("/show")
public class ShowController {

    @Resource
    private IShowService showService;

    /** 演出详情（走缓存，逻辑过期方案） */
    @GetMapping("/{id}")
    public Result queryShowById(@PathVariable("id") Long id) {
        return showService.queryById(id);
    }

    /** 新增演出 */
    @PostMapping
    public Result saveShow(@RequestBody Show show) {
        showService.save(show);
        return Result.ok(show.getId());
    }

    /** 更新演出（先更库后删缓存） */
    @PutMapping
    public Result updateShow(@RequestBody Show show) {
        return showService.update(show);
    }

    /** 按分类分页查询，带坐标时查附近演出（GEO） */
    @GetMapping("/of/type")
    public Result queryShowByType(
            @RequestParam("typeId") Integer categoryId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "x", required = false) Double x,
            @RequestParam(value = "y", required = false) Double y) {
        return showService.queryShowByType(categoryId, current, x, y);
    }

    /** 按名称搜索演出 */
    @GetMapping("/of/name")
    public Result queryShowByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return showService.queryShowByName(name, current);
    }
}
