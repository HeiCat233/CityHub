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
 * 票档库存（开票场次），与票档一对一
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_tier_stock")
public class TierStock implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 票档id */
    @TableId(value = "tier_id", type = IdType.INPUT)
    private Long tierId;

    /** 演出id */
    private Long showId;

    /** 剩余库存 */
    private Integer stock;

    /** 开票开始时间 */
    private LocalDateTime beginTime;

    /** 开票结束时间 */
    private LocalDateTime endTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
