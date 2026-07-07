package com.amz.service;

import com.amz.model.HijackAlert;
import com.amz.model.KeywordRankRecord;
import com.amz.model.NegativeReviewAlert;

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
     * 查询差评告警列表。
     */
    List<NegativeReviewAlert> listNegativeReviewAlerts(Long shopId, String status);

    /**
     * 标记差评告警已处理。
     */
    boolean handleNegativeReviewAlert(Long alertId);

    /**
     * 扫描跟卖，返回新增告警数。
     */
    int scanHijackers(Long shopId);

    /**
     * 查询跟卖告警列表。
     */
    List<HijackAlert> listHijackAlerts(Long shopId, String status);

    /**
     * 抓取关键词排名快照，返回抓取记录数。
     */
    int captureKeywordRanks(Long shopId);

    /**
     * 查询某关键词+ASIN 的排名趋势。
     */
    List<KeywordRankRecord> getRankTrend(Long shopId, String keyword, String asin);
}
