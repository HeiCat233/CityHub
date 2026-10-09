package com.tickethub.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tickethub.dto.Result;
import com.tickethub.entity.Post;

/**
 * 演出动态服务
 */
public interface IPostService extends IService<Post> {

    /** 发布动态，并推送到粉丝收件箱 */
    Result savePost(Post post);

    /** 按id查询动态详情 */
    Result queryPostById(Long id);

    /** 点赞 / 取消点赞 */
    Result updateLike(Long id);

    /** 点赞用户列表（top5） */
    Result queryPostLikes(Long id);

    /** 热门动态 */
    Result queryHotPost(Integer current);

    /** 关注人的动态（Feed 流滚动分页） */
    Result queryPostOfFollow(Long max, Integer offset);
}
