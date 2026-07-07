package com.amz.scheduler;

import com.amz.service.OpsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 运营监控定时扫描器。
 * <p>
 * 每天早 8 点扫描差评 + 跟卖；每 6 小时抓取关键词排名。
 * 生产环境应通过 SP-API / 爬虫抓取真实数据，此处模拟触发流程。
 */
@Slf4j
@Component
public class OpsMonitorScheduler {

    @Autowired
    private OpsService opsService;

    /**
     * 每天早 8 点扫描差评 + 跟卖（cron: 0 0 8 * * ?）。
     */
    @Scheduled(cron = "0 0 8 * * ?")
    public void dailyScan() {
        log.info("运营监控定时任务启动：差评 + 跟卖扫描");
        try {
            int reviewAlerts = opsService.scanNegativeReviews(1L);
            int hijackAlerts = opsService.scanHijackers(1L);
            log.info("运营监控完成：新增差评告警 {} 条，跟卖告警 {} 条", reviewAlerts, hijackAlerts);
        } catch (Exception e) {
            log.error("运营监控定时任务异常", e);
        }
    }

    /**
     * 每 6 小时抓取关键词排名（cron 0 0 斜杠6 星 星 问）。
     */
    @Scheduled(cron = "0 0 */6 * * ?")
    public void rankCapture() {
        log.info("关键词排名抓取任务启动");
        try {
            int captured = opsService.captureKeywordRanks(1L);
            log.info("关键词排名抓取完成：{} 条记录", captured);
        } catch (Exception e) {
            log.error("关键词排名抓取异常", e);
        }
    }
}
