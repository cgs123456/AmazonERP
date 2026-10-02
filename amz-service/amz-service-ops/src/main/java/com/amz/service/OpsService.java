package com.amz.service;

import com.amz.model.HijackAlert;
import com.amz.model.KeywordRankRecord;
import com.amz.model.NegativeReviewAlert;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;

import java.util.List;

/**
 * 运营工具服务接口：差评监控 + 跟卖监控 + 关键词排名追踪。
 */
public interface OpsService {

    /**
     * 扫描差评（≤3星），返回新增告警数。
     */
    int scanNegativeReviews(Long shopId);

    /**
     * 查询差评告警列表（keyset 分页，按 id 倒序）。
     */
    PageResult<NegativeReviewAlert> listNegativeReviewAlerts(Long shopId, String status, PageRequest page);

    /**
     * 标记差评告警已处理。告警不存在、不属于当前用户授权店铺、或已经不是 NEW 时
     * 抛业务错误，不返回 false——这条端点没有 {@code @ShopScoped}（alertId 不是 shopId），
     * 服务内的逐行严格判定是唯一防线，必须让调用方看得见。
     */
    boolean handleNegativeReviewAlert(Long alertId);

    /**
     * 扫描跟卖，返回新增告警数。
     */
    int scanHijackers(Long shopId);

    /**
     * 查询跟卖告警列表（keyset 分页，按 id 倒序）。
     */
    PageResult<HijackAlert> listHijackAlerts(Long shopId, String status, PageRequest page);

    /**
     * 抓取关键词排名快照，返回抓取记录数。
     */
    int captureKeywordRanks(Long shopId);

    /**
     * 查询某关键词+ASIN 的排名趋势：返回最近若干个点，按抓取时间升序。
     */
    List<KeywordRankRecord> getRankTrend(Long shopId, String keyword, String asin);
}
