package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("amz_keyword_ranking")
public class KeywordRanking {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shopId;
    private String asin;
    private String keyword;
    private Integer organicRank;
    private Integer adRank;
    private Integer searchVolume;
    private LocalDate rankDate;
    private String marketplaceId;
    private LocalDateTime createTime;
}
