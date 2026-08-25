package com.amz.scheduler;

import com.amz.mapper.ShopMapper;
import com.amz.model.Shop;
import com.amz.service.OpsService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 运营监控定时扫描器（<b>仅 mock profile 注册</b>）。
 * <p>
 * 每天早 8 点扫描差评 + 跟卖；每 6 小时抓取关键词排名。
 * <p>
 * ⚠️ 当前 {@code OpsService} 的扫描实现为<b>模拟数据生成</b>（随机 ASIN/虚构差评/随机排名），
 * 落库后与真实告警无法区分，会污染生产数据。故本调度器限定 {@code mock} profile：
 * <ul>
 *   <li>mock 环境：正常注册运行，供演示/联调；</li>
 *   <li>非 mock（默认）：不注册，不再自动生成假告警。</li>
 * </ul>
 * 真实抓取接入点：SP-API Reviews / Listings + 关键词排名爬虫服务，
 * 接入后移除本注解并替换 OpsService 实现。
 */
@Slf4j
@Component
@Profile("mock")
public class OpsMonitorScheduler {

    @Autowired
    private OpsService opsService;

    @Autowired
    private ShopMapper shopMapper;

    /**
     * 每天早 8 点扫描差评 + 跟卖（cron: 0 0 8 * * ?）。
     * 遍历所有已授权店铺，单店失败不影响其他店铺。
     */
    @Scheduled(cron = "0 0 8 * * ?")
    public void dailyScan() {
        log.info("运营监控定时任务启动：差评 + 跟卖扫描");
        List<Shop> shops = listActiveShops();
        int totalReviewAlerts = 0;
        int totalHijackAlerts = 0;
        for (Shop shop : shops) {
            Long shopId = shop.getId();
            try {
                int reviewAlerts = opsService.scanNegativeReviews(shopId);
                int hijackAlerts = opsService.scanHijackers(shopId);
                totalReviewAlerts += reviewAlerts;
                totalHijackAlerts += hijackAlerts;
                log.info("店铺 {} 扫描完成：差评告警 {} 条，跟卖告警 {} 条",
                        shopId, reviewAlerts, hijackAlerts);
            } catch (Exception e) {
                log.error("店铺 {} 运营监控扫描异常", shopId, e);
            }
        }
        log.info("运营监控完成：共扫描 {} 个店铺，新增差评告警 {} 条，跟卖告警 {} 条",
                shops.size(), totalReviewAlerts, totalHijackAlerts);
    }

    /**
     * 每 6 小时抓取关键词排名（cron 0 0 斜杠6 星 星 问）。
     * 遍历所有已授权店铺，单店失败不影响其他店铺。
     */
    @Scheduled(cron = "0 0 */6 * * ?")
    public void rankCapture() {
        log.info("关键词排名抓取任务启动");
        List<Shop> shops = listActiveShops();
        int totalCaptured = 0;
        for (Shop shop : shops) {
            Long shopId = shop.getId();
            try {
                int captured = opsService.captureKeywordRanks(shopId);
                totalCaptured += captured;
                log.info("店铺 {} 关键词排名抓取完成：{} 条记录", shopId, captured);
            } catch (Exception e) {
                log.error("店铺 {} 关键词排名抓取异常", shopId, e);
            }
        }
        log.info("关键词排名抓取完成：共扫描 {} 个店铺，{} 条记录", shops.size(), totalCaptured);
    }

    /**
     * 查询所有已授权（status=1）的店铺列表。
     * 查询异常时返回空列表，避免阻断定时任务。
     */
    private List<Shop> listActiveShops() {
        try {
            LambdaQueryWrapper<Shop> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Shop::getStatus, 1);
            List<Shop> shops = shopMapper.selectList(wrapper);
            if (shops.isEmpty()) {
                log.warn("未查询到已授权的店铺，跳过扫描");
            }
            return shops;
        } catch (Exception e) {
            log.error("查询已授权店铺列表异常", e);
            return Collections.emptyList();
        }
    }
}