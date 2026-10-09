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
 * 购票订单
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_ticket_order")
public class TicketOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单id（全局唯一ID） */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 下单用户id */
    private Long userId;

    /** 演出id */
    private Long showId;

    /** 票档id */
    private Long tierId;

    /** 成交单价（分） */
    private Long price;

    /** 购买数量 */
    private Integer quantity;

    /** 支付方式：0 未支付，1 余额，2 支付宝，3 微信 */
    private Integer payType;

    /** 状态：1 待支付，2 已支付，3 已出票，4 已取消，5 退款中，6 已退款 */
    private Integer status;

    /** 下单时间 */
    private LocalDateTime createTime;

    /** 支付时间 */
    private LocalDateTime payTime;

    /** 关单时间 */
    private LocalDateTime closeTime;

    private LocalDateTime updateTime;
}
