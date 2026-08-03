package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("amz_platform_product")
public class PlatformProduct implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shopId;
    private String platform;
    private String platformProductId;
    private String platformProductSku;
    private String amazonAsin;
    private String amazonSku;
    private String title;
    private BigDecimal price;
    private String currency;
    private Integer stockQty;
    private String status;
    private String imageUrl;
    private String category;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
