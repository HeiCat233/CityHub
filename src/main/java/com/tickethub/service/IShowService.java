package com.tickethub.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tickethub.dto.Result;
import com.tickethub.entity.Show;

/**
 * 演出服务
 */
public interface IShowService extends IService<Show> {

    /** 按id查询演出详情（走缓存） */
    Result queryById(Long id);

    /** 更新演出并删除缓存 */
    Result update(Show show);

    /** 按分类查询演出，可选按坐标查附近演出 */
    Result queryShowByType(Integer categoryId, Integer current, Double x, Double y);

    /** 按名称搜索演出 */
    Result queryShowByName(String keyword, Integer current);
}
