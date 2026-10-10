package com.amz.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 本店被追踪的 (关键词, ASIN) 组合目录条目。
 *
 * <p>{@code GET /ops/rank/trend} 要求 keyword 与 asin 都必填，而此前没有端点能列出
 * 「本店到底追踪了哪些组合」——运营只能凭记忆输入字面完全一致的关键词，输错一个空格
 * 就是「没有记录」，与「真的没抓过」无法区分。这个条目把可选项从表里读出来，
 * 让趋势页从填空题变成选择题。
 *
 * <p>唯一数据来源是 {@code amz_keyword_rank} 表本身：只有抓过的组合才存在。
 * 不另建「追踪清单」表——那会多出一个必须维护、却无法验证是否与真实抓取一致的真相源。
 */
@Data
public class TrackedKeyword implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 追踪关键词，与 amz_keyword_rank.keyword 字面一致 */
    private String keyword;

    /** 商品 ASIN */
    private String asin;

    /** 该组合已积累的排名点数 */
    private int pointCount;

    /** 最后一次抓取时的排名位置（不是最好值也不是最差值） */
    private Integer latestRank;

    /** 最后一次抓取时刻，'yyyy-MM-dd HH:mm:ss' 字符串，与表列同格式 */
    private String lastCaptureTime;

    /** 搜索站点，取最后一次抓取记录上的值 */
    private String marketplace;
}
