package com.amz.service;

import com.amz.dto.DashboardReport;

import java.util.List;
import java.util.Map;

/**
 * 数据报表服务接口。
 */
public interface ReportService {

    /**
     * 获取店铺仪表盘综合报表（多维度可视化数据）。
     *
     * @param shopId   店铺 ID
     * @param dateRange 日期范围：7d / 30d / 90d
     */
    DashboardReport getDashboard(Long shopId, String dateRange);

    /**
     * 销售额趋势（按日聚合）。
     * <p>
     * 前端 GET /report/dashboard/sales-trend?shopId=&days= 调用，
     * 返回元素结构：{@code { "day": "yyyy-MM-dd", "value": 1234.56 }}。
     *
     * @param shopId 店铺 ID（可为 null，表示聚合全部店铺）
     * @param days   最近 N 天（默认 7）
     */
    List<Map<String, Object>> getSalesTrend(Long shopId, Integer days);

    /**
     * 店铺销售额分布。
     * <p>
     * 前端 GET /report/dashboard/shop-distribution 调用，
     * 返回元素结构：{@code { "name": "店铺名", "percent": 45.6, "color": "#5470C6" }}。
     *
     * @param shopId 店铺 ID（可为 null，表示全部店铺）
     */
    List<Map<String, Object>> getShopDistribution(Long shopId);
}
