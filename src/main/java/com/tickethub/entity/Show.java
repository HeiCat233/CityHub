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
 * 演出
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_show")
public class Show implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 演出名称 */
    private String name;

    /** 演出分类id */
    private Long categoryId;

    /** 场馆名称 */
    private String venueName;

    /** 场馆地址 */
    private String venueAddress;

    /** 城市 */
    private String city;

    /** 经度 */
    private Double x;

    /** 纬度 */
    private Double y;

    /** 演出海报，多张以逗号分隔 */
    private String images;

    /** 演出介绍 */
    private String description;

    /** 演出时长（分钟） */
    private Integer duration;

    /** 演出开始时间 */
    private LocalDateTime beginTime;

    /** 开票时间 */
    private LocalDateTime saleBeginTime;

    /** 停售时间 */
    private LocalDateTime saleEndTime;

    /** 最低票价（分） */
    private Long minPrice;

    /** 已售数量 */
    private Integer sold;

    /** 评分，乘10保存 */
    private Integer score;

    /** 评论数量 */
    private Integer comments;

    /** 状态：1 未开票，2 售票中，3 已售罄，4 已结束 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 距离（米），GEO 查询回填，不落库 */
    @TableField(exist = false)
    private Double distance;
}
