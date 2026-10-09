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
 * 票档
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_ticket_tier")
public class TicketTier implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 演出id */
    private Long showId;

    /** 票档名称，如 内场VIP / 看台A区 */
    private String name;

    /** 票价（分） */
    private Long price;

    /** 购票须知 */
    private String rules;

    /** 类型：1 普通票档，2 秒杀票档 */
    private Integer type;

    /** 状态：1 正常，2 下架 */
    private Integer status;

    /** 库存（创建秒杀票档时传入，不落库） */
    @TableField(exist = false)
    private Integer stock;

    /** 开票开始时间（创建秒杀票档时传入，不落库） */
    @TableField(exist = false)
    private LocalDateTime beginTime;

    /** 开票结束时间（创建秒杀票档时传入，不落库） */
    @TableField(exist = false)
    private LocalDateTime endTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
