package com.tickethub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 动态评论
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_post_comment")
public class PostComment implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户id */
    private Long userId;

    /** 动态id */
    private Long postId;

    /** 一级评论id，0 表示一级评论 */
    private Long parentId;

    /** 回复的评论id */
    private Long answerId;

    /** 回复内容 */
    private String content;

    /** 点赞数 */
    private Integer liked;

    /** 状态：0 正常，1 被举报，2 禁止查看 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
