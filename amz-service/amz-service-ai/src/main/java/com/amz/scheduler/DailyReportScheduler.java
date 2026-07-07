package com.amz.scheduler;

import com.amz.agent.ProactiveReminderService;
import com.amz.mapper.UserPreferenceMapper;
import com.amz.model.UserPreference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Agent 定时任务调度器。
 * <p>
 * 提供两类定时能力：
 * <ol>
 *   <li>每日昨日运营报告推送（默认每天早 8 点）</li>
 *   <li>主动提醒扫描（每 4 小时一次，针对活跃用户）</li>
 * </ol>
 * 生产环境报告应通过 Feign 调用各业务模块聚合数据 + 推送到消息中心（amz-service-message），
 * 此处模拟报告生成流程并打印到日志，保证项目可独立运行。
 */
@Slf4j
@Component
public class DailyReportScheduler {

    @Autowired
    private UserPreferenceMapper userPreferenceMapper;

    @Autowired
    private ProactiveReminderService proactiveReminderService;

    /**
     * 每日昨日运营报告（cron: 0 0 8 * * ?，每天早 8 点）。
     * <p>
     * 注意：注释中不能包含 cron 表达式中的斜杠星号序列，否则会被解析为 Javadoc 结束符。
     * 真实 cron 为 0 0 8 星 星 问。
     */
    @Scheduled(cron = "${agent.daily-report-cron:0 0 8 * * ?}")
    public void pushDailyReport() {
        log.info("=== 每日运营报告推送任务启动 ===");
        LocalDate yesterday = LocalDate.now().minusDays(1);

        LambdaQueryWrapper<UserPreference> wrapper = new LambdaQueryWrapper<>();
        wrapper.isNotNull(UserPreference::getLastActiveTime);
        List<UserPreference> users = userPreferenceMapper.selectList(wrapper);

        for (UserPreference pref : users) {
            String report = buildReport(pref, yesterday);
            log.info("推送昨日运营报告至用户 {} (店铺 {})：\n{}",
                    pref.getUserId(), pref.getPreferredShopId(), report);
            // TODO: 通过 Feign 调用 amz-service-message 推送到用户的消息中心
        }
        log.info("=== 每日运营报告推送完成：覆盖用户 {} 人 ===", users.size());
    }

    /**
     * 主动提醒扫描（每 4 小时一次）。
     * 真实 cron 为 0 0 斜杠4 星 星 问。
     */
    @Scheduled(cron = "0 0 0/4 * * ?")
    public void proactiveReminderScan() {
        log.info("=== 主动提醒扫描任务启动 ===");
        List<String> reminders = proactiveReminderService.scanAndRemind();
        for (String r : reminders) {
            log.info("主动提醒：{}", r);
        }
        log.info("=== 主动提醒扫描完成：生成提醒 {} 条 ===", reminders.size());
    }

    /**
     * 生成单用户昨日运营报告（模拟）。
     * <p>
     * 报告结构：
     * <pre>
     * 1. 销售概览：订单数 / 销售额 / ACoS
     * 2. 库存预警：紧急补货 SKU 列表
     * 3. 客服概览：新增工单 / 待回复数
     * 4. 多平台聚合：Temu / TikTok / Shein 订单数
     * 5. 建议行动
     * </pre>
     */
    private String buildReport(UserPreference pref, LocalDate date) {
        Long shopId = pref.getPreferredShopId();
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════ 昨日运营报告 ═══════════════\n");
        sb.append("日期：").append(date).append("    店铺 ID：").append(shopId).append("\n\n");

        // 1. 销售概览
        sb.append("【销售概览】\n");
        sb.append("  订单数：23 单（环比 +15%）\n");
        sb.append("  销售额：$1,234.56（环比 +12.4%）\n");
        sb.append("  广告花费：$156.80 / 广告销售额：$628.40\n");
        sb.append("  ACoS：24.95%（健康区间）\n\n");

        // 2. 库存预警
        sb.append("【库存预警】\n");
        sb.append("  紧急补货 SKU：3 个\n");
        sb.append("    - B08X4-001：可售 4 天（建议补货 200 件）\n");
        sb.append("    - B08X4-005：可售 6 天（建议补货 150 件）\n");
        sb.append("    - B08X4-008：可售 5 天（建议补货 80 件）\n\n");

        // 3. 客服概览
        sb.append("【客服概览】\n");
        sb.append("  新增工单：5 条（2 条紧急）\n");
        sb.append("  待回复：3 条（最长等待 6 小时）\n");
        sb.append("  差评新增：1 条（SKU B08X4-002，2 星）\n\n");

        // 4. 多平台聚合
        sb.append("【多平台聚合】\n");
        sb.append("  Amazon：18 单 / $986.50\n");
        sb.append("  Temu：3 单 / $89.94\n");
        sb.append("  TikTok Shop：1 单 / £89.99\n");
        sb.append("  Shein：1 单 / €19.50\n\n");

        // 5. 建议行动
        sb.append("【今日建议行动】\n");
        sb.append("  1. 立即处理 3 个紧急补货 SKU，建议通过 1688 一键下单\n");
        sb.append("  2. 回复 3 条待回复工单（最长等待已超阈值）\n");
        sb.append("  3. 针对 B08X4-002 差评发起品控流程\n");
        sb.append("  4. TikTok Shop 单量待提升，建议投放达人短视频\n");
        sb.append("═══════════════════════════════════════════");

        return sb.toString();
    }
}
