package com.tickethub.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tickethub.dto.Result;
import com.tickethub.entity.Follow;

/**
 * 关注服务
 */
public interface IFollowService extends IService<Follow> {

    /** 关注 / 取关 */
    Result follow(Long followUserId, Boolean isFollow);

    /** 是否已关注 */
    Result isFollow(Long followUserId);

    /** 共同关注 */
    Result followCommons(Long id);
}
