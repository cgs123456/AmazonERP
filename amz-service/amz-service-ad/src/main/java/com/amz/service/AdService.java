package com.amz.service;

import com.amz.model.AdReport;
import com.amz.model.BidSchedule;
import com.amz.optimizer.KeywordOptimizer;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;

import java.util.List;
import java.util.Map;

/**
 * 广告管理服务接口。
 */
public interface AdService {

    /**
     * 分页查询店铺广告报表（活动级），含 ACoS/ROAS 等派生指标。
     * 聚合和截断都在数据库层完成，不把全店日报加载进 JVM。
     */
    PageResult<AdReport> getShopReports(Long shopId, PageRequest page);

    /**
     * 查询店铺整体汇总指标。
     */
    AdReport getShopSummary(Long shopId);

    /**
     * 查询店铺近 N 天每日 ACoS 趋势（供前端折线图）。
     * <p>
     * 数据源为 amz_ad_daily_report 日报表，按 reportDate 汇总当日 cost/sales 后计算 ACoS；
     * 无数据的日期跳过不断轴。表空或未初始化时返回空列表，调用方保留降级展示。
     *
     * @param shopId 店铺 ID（null 直接返回空列表）
     * @param days   近 N 天（null/非法时取 14，上限 90）
     * @param adType 广告类型过滤（SP/SB/SD/DSP，null/空表示全类型汇总；
     *               依赖日报落库时由 campaign_ext 表回填）
     * @return 按日期升序的 {@code [{day: yyyy-MM-dd, value: ACoS%}]} 列表
     */
    List<Map<String, Object>> getAdTrend(Long shopId, Integer days, String adType);

    /**
     * 生成关键词优化建议。
     */
    List<KeywordOptimizer.Suggestion> optimizeKeywords(Long shopId, String campaignId);

    /**
     * 创建分时调价规则。
     */
    BidSchedule createBidSchedule(BidSchedule schedule);

    /**
     * 修改分时调价规则：按 id 反查归属，请求体里的 shopId 不作为放行依据。
     */
    BidSchedule updateBidSchedule(BidSchedule schedule);

    /**
     * 启用/停用分时调价规则。停用后下一个整点起就不再改价。
     */
    boolean toggleBidSchedule(Long id, boolean enabled);

    /**
     * 删除分时调价规则。
     */
    boolean deleteBidSchedule(Long id);

    /**
     * 查询店铺的分时调价规则。
     */
    PageResult<BidSchedule> listBidSchedules(Long shopId, PageRequest page);
}
