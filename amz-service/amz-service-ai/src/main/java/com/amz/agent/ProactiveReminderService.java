package com.amz.agent;

import com.amz.mapper.UserPreferenceMapper;
import com.amz.model.UserPreference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 主动提醒服务。
 * <p>
 * Agent 记忆化的"主动侧"：基于用户偏好与近 24 小时业务异动，主动生成提醒消息。
 * <p>
 * 触发规则（参考领星/积加的运营提醒）：
 * <ol>
 *   <li>库存异动：偏好店铺下某 SKU 库存可售天数 &lt; 阈值 → 紧急补货提醒</li>
 *   <li>销量下滑：偏好店铺昨日销量环比下降 &gt; 阈值 → 销量预警</li>
 *   <li>差评告警：偏好店铺近 24 小时收到 1-3 星差评 → 品控提醒</li>
 *   <li>跟卖告警：偏好店铺 Listing 被跟卖 → 抢回 Buy Box 提醒</li>
 * </ol>
 * 生产环境应通过 Feign 调用 ops/report 等微服务拉取真实数据，此处模拟触发流程。
 */
@Slf4j
@Service
public class ProactiveReminderService {

    @Autowired
    private UserPreferenceMapper userPreferenceMapper;

    @Value("${agent.reminder.inventory-days-threshold:7}")
    private int inventoryDaysThreshold;

    @Value("${agent.reminder.sales-drop-ratio-threshold:0.20}")
    private double salesDropRatioThreshold;

    /**
     * 扫描所有活跃用户（24 小时内有交互）的偏好店铺，生成主动提醒。
     *
     * @return 提醒消息列表（每用户 0-N 条）
     */
    public List<String> scanAndRemind() {
        List<String> reminders = new ArrayList<>();
        LocalDateTime since = LocalDateTime.now().minusDays(1);

        LambdaQueryWrapper<UserPreference> wrapper = new LambdaQueryWrapper<>();
        wrapper.isNotNull(UserPreference::getLastActiveTime)
               .ge(UserPreference::getLastActiveTime, since);
        List<UserPreference> activeUsers = userPreferenceMapper.selectList(wrapper);

        for (UserPreference pref : activeUsers) {
            reminders.addAll(remindForUser(pref));
        }
        log.info("主动提醒扫描完成：活跃用户 {} 人，生成提醒 {} 条", activeUsers.size(), reminders.size());
        return reminders;
    }

    /**
     * 为单个用户生成提醒（基于其偏好店铺）。
     */
    private List<String> remindForUser(UserPreference pref) {
        List<String> list = new ArrayList<>();
        Long shopId = pref.getPreferredShopId();
        if (shopId == null) {
            return list;
        }

        // 1. 库存异动提醒（模拟）
        list.add(String.format(
                "【库存提醒】用户 %s，店铺 %d 下 SKU B08X4-001 当前可售 4 天（日均 8 件，库存 32 件），低于阈值 %d 天，请尽快补货。",
                pref.getNickname() != null ? pref.getNickname() : pref.getUserId(),
                shopId, inventoryDaysThreshold));

        // 2. 销量下滑提醒（模拟）
        list.add(String.format(
                "【销量预警】用户 %s，店铺 %d 昨日订单 18 单，较前日 25 单环比下降 28%%，超过阈值 %.0f%%。",
                pref.getNickname() != null ? pref.getNickname() : pref.getUserId(),
                shopId, salesDropRatioThreshold * 100));

        // 3. 差评告警（模拟，仅品类相关时推送）
        if (pref.getPreferredCategory() != null) {
            list.add(String.format(
                    "【差评告警】用户 %s（关注品类：%s），店铺 %d 收到 1 条 2 星差评，涉及 SKU B08X4-002，请关注品控。",
                    pref.getNickname() != null ? pref.getNickname() : pref.getUserId(),
                    pref.getPreferredCategory(), shopId));
        }

        // 4. 跟卖告警（模拟）
        list.add(String.format(
                "【跟卖告警】用户 %s，店铺 %d 的 ASIN B08X4-003 被新增 1 个跟卖者，建议立即投诉抢回 Buy Box。",
                pref.getNickname() != null ? pref.getNickname() : pref.getUserId(),
                shopId));
        return list;
    }
}
