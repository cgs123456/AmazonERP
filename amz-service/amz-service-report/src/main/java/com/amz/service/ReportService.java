package com.amz.service;

import com.amz.dto.DashboardReport;

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
}
