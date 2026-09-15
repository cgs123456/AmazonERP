package com.amz.service;

import com.amz.dto.CarrierPerformanceDTO;
import com.amz.dto.DashboardOverview;
import com.amz.dto.ShipmentAlertDTO;
import com.amz.dto.TrendPointDTO;

import java.util.List;

/**
 * 物流看板聚合服务。
 * <p>
 * <b>为什么需要独立聚合层：</b>既有接口全是「列表 / 明细 / 单据操作」，
 * 前端要出一个看板就得把整张货件表拉下来自己算——既慢，又会因为各页面各算一遍
 * 而出现口径不一致（比如「延误」在一处含异常、另一处不含）。
 * 聚合口径收在此层，看板与后续报表共用同一份定义。
 * <p>
 * 所有方法都要求 shopId，不做全库聚合：看板是店铺级运营视图，
 * 跨店汇总既无业务意义，也会成为越权读取他店数据的通道。
 */
public interface LogisticsDashboardService {

    /** 概览：状态分布、需处理项计数、平均头程时效与数据新鲜度 */
    DashboardOverview overview(Long shopId);

    /**
     * 趋势：近 N 天的建单量与送达量。
     *
     * @param days 统计天数，服务内会夹取到合理区间
     */
    List<TrendPointDTO> trend(Long shopId, int days);

    /** 承运商维度时效与延误率对比 */
    List<CarrierPerformanceDTO> carrierPerformance(Long shopId);

    /** 需人工介入的货件清单（替代逐个点开查轨迹的人工检索） */
    List<ShipmentAlertDTO> alerts(Long shopId);
}
