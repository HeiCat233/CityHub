package com.tickethub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 演出动态
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_post")
public class Post implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 演出id */
    private Long showId;

    /** 用户id */
    private Long userId;

    /** 用户头像（关联查询，不落库） */
    @TableField(exist = false)
    private String icon;

    /** 用户昵称（关联查询，不落库） */
    @TableField(exist = false)
    private String name;

    /** 当前用户是否已点赞（不落库） */
    @TableField(exist = false)
    private Boolean isLike;

    /** 标题 */
    private String title;

    /** 图片，多张以逗号分隔 */
    private String images;

    /** 正文 */
    private String content;

    /** 点赞数量 */
    private Integer liked;

    /** 评论数量 */
    private Integer comments;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
