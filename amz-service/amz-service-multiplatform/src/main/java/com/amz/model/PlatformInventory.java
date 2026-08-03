package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("amz_platform_inventory")
public class PlatformInventory implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shopId;
    private String platform;
    private String platformProductId;
    private String sku;
    private String warehouse;
    private Integer availableQty;
    private Integer reservedQty;
    private Integer inboundQty;
    private LocalDateTime snapshotTime;
    private LocalDateTime createTime;
}
