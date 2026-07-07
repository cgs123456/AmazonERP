package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * 关键词排名追踪记录实体。
 * 每次抓取生成一条快照，按 keyword + asin + 抓取时间维度记录自然排名位置。
 * 前端按时间序列绘制排名趋势曲线。
 */
@Data
@TableName("amz_keyword_rank")
public class KeywordRankRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    /** 追踪关键词 */
    private String keyword;

    /** 商品 ASIN */
    private String asin;

    /** 自然排名位置（1=首页第1名，>48=非首页） */
    private Integer rank;

    /** 搜索站点：US/UK/DE/JP 等 */
    private String marketplace;

    /** 抓取时间（ISO 格式） */
    private String captureTime;
}
