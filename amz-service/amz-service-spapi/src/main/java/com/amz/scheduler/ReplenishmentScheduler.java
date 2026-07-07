package com.amz.scheduler;

import com.amz.credential.ShopCredentialStore;
import com.amz.engine.ReplenishmentEngine;
import com.amz.mapper.FbaInventoryMapper;
import com.amz.mapper.ReplenishmentSuggestionMapper;
import com.amz.model.FbaInventory;
import com.amz.model.ReplenishmentSuggestion;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 智能补货定时调度器。
 * <p>
 * 每天 06:00 全量重算所有活跃店铺的补货建议，按 (shopId, sku, today) 唯一键 upsert 入库。
 * 单店失败不影响其他店铺。
 */
@Component
public class ReplenishmentScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReplenishmentScheduler.class);

    /**
     * 默认类目（实际应从 amz_product 表查询，此处简化为 ELECTRONICS）。
     */
    private static final String DEFAULT_CATEGORY = "ELECTRONICS";

    /**
     * 默认采购周期（天）。
     */
    private static final int DEFAULT_LEAD_TIME_DAYS = 30;

    @Autowired
    private ReplenishmentEngine replenishmentEngine;

    @Autowired
    private FbaInventoryMapper fbaInventoryMapper;

    @Autowired
    private ReplenishmentSuggestionMapper replenishmentSuggestionMapper;

    @Autowired
    private ShopCredentialStore shopCredentialStore;

    /**
     * 每天 06:00 全量重算补货建议。
     */
    @Scheduled(cron = "0 0 6 * * *")
    public void dailyReplenishmentCalc() {
        Set<Long> shopIds = shopCredentialStore.getActiveShopIds();
        if (shopIds == null || shopIds.isEmpty()) {
            log.info("dailyReplenishmentCalc: no active shops, skipping");
            return;
        }
        log.info("dailyReplenishmentCalc start: shopCount={}", shopIds.size());
        for (Long shopId : shopIds) {
            try {
                int count = calcShopReplenishment(shopId);
                log.info("dailyReplenishmentCalc shopId={} suggestions={}", shopId, count);
            } catch (Exception e) {
                log.error("dailyReplenishmentCalc failed shopId={}", shopId, e);
            }
        }
        log.info("dailyReplenishmentCalc done");
    }

    /**
     * 计算单个店铺所有 SKU 的补货建议并落库。供定时任务与手动触发共用。
     *
     * @param shopId 店铺 ID
     * @return 本次生成的补货建议条数
     */
    public int calcShopReplenishment(Long shopId) {
        List<FbaInventory> inventories = fbaInventoryMapper.selectList(
                new LambdaQueryWrapper<FbaInventory>()
                        .eq(FbaInventory::getShopId, shopId));
        if (inventories == null || inventories.isEmpty()) {
            log.warn("calcShopReplenishment shopId={}: no inventory records", shopId);
            return 0;
        }
        int count = 0;
        for (FbaInventory inv : inventories) {
            try {
                int available = inv.getAvailableQuantity() == null ? 0 : inv.getAvailableQuantity();
                int inboundWorking = inv.getInboundWorking() == null ? 0 : inv.getInboundWorking();
                int inboundShipped = inv.getInboundShipped() == null ? 0 : inv.getInboundShipped();
                int currentTotalStock = available + inboundWorking + inboundShipped;

                ReplenishmentSuggestion suggestion = replenishmentEngine.generateSuggestion(
                        shopId, inv.getSku(), inv.getAsin(), DEFAULT_CATEGORY,
                        currentTotalStock, DEFAULT_LEAD_TIME_DAYS);
                upsert(suggestion);
                count++;
            } catch (Exception e) {
                log.error("calcShopReplenishment failed shopId={} sku={}", shopId, inv.getSku(), e);
            }
        }
        return count;
    }

    /**
     * upsert：按 (shopId, sku, statDate) 唯一键查询，存在则 updateById，否则 insert。
     */
    private void upsert(ReplenishmentSuggestion suggestion) {
        ReplenishmentSuggestion existing = replenishmentSuggestionMapper.selectOne(
                new LambdaQueryWrapper<ReplenishmentSuggestion>()
                        .eq(ReplenishmentSuggestion::getShopId, suggestion.getShopId())
                        .eq(ReplenishmentSuggestion::getSku, suggestion.getSku())
                        .eq(ReplenishmentSuggestion::getStatDate, suggestion.getStatDate()));
        if (existing != null) {
            suggestion.setId(existing.getId());
            replenishmentSuggestionMapper.updateById(suggestion);
        } else {
            replenishmentSuggestionMapper.insert(suggestion);
        }
    }
}
