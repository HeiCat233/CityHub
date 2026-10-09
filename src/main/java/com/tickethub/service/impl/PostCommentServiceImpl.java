package com.tickethub.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tickethub.entity.PostComment;
import com.tickethub.mapper.PostCommentMapper;
import com.tickethub.service.IPostCommentService;
import org.springframework.stereotype.Service;

/**
 * 动态评论服务实现
 */
@Service
public class PostCommentServiceImpl extends ServiceImpl<PostCommentMapper, PostComment> implements IPostCommentService {
}
