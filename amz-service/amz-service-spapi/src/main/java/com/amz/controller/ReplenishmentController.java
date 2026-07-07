package com.amz.controller;

import com.amz.mapper.ReplenishmentSuggestionMapper;
import com.amz.model.ReplenishmentSuggestion;
import com.amz.result.Result;
import com.amz.scheduler.ReplenishmentScheduler;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 智能补货建议对外接口。
 * <p>
 * 提供按店铺查询补货建议列表、手动触发补货计算、查询紧急补货建议三类能力。
 */
@RestController
@RequestMapping("/replenish")
public class ReplenishmentController {

    private static final Logger log = LoggerFactory.getLogger(ReplenishmentController.class);

    /**
     * 紧急级别常量。
     */
    private static final String URGENCY_URGENT = "URGENT";

    @Autowired
    private ReplenishmentSuggestionMapper replenishmentSuggestionMapper;

    @Autowired
    private ReplenishmentScheduler replenishmentScheduler;

    /**
     * 查询指定店铺所有补货建议列表。
     *
     * @param shopId 店铺 ID
     * @return 补货建议列表
     */
    @GetMapping("/list/{shopId}")
    public Result<List<ReplenishmentSuggestion>> list(@PathVariable Long shopId) {
        if (shopId == null) {
            return Result.failure("shopId must not be null");
        }
        List<ReplenishmentSuggestion> list = replenishmentSuggestionMapper.selectList(
                new LambdaQueryWrapper<ReplenishmentSuggestion>()
                        .eq(ReplenishmentSuggestion::getShopId, shopId)
                        .orderByDesc(ReplenishmentSuggestion::getUrgencyLevel));
        return Result.success(list);
    }

    /**
     * 手动触发指定店铺的补货计算。
     *
     * @param shopId 店铺 ID
     * @return 本次生成的补货建议条数
     */
    @PostMapping("/calc/{shopId}")
    public Result<Integer> calc(@PathVariable Long shopId) {
        if (shopId == null) {
            return Result.failure("shopId must not be null");
        }
        try {
            int count = replenishmentScheduler.calcShopReplenishment(shopId);
            log.info("manual replenish calc shopId={} count={}", shopId, count);
            return Result.success(count);
        } catch (Exception e) {
            log.error("manual replenish calc failed shopId={}", shopId, e);
            return Result.failure("calc failed: " + e.getMessage());
        }
    }

    /**
     * 查询指定店铺的紧急（URGENT）级别补货建议。
     *
     * @param shopId 店铺 ID
     * @return 紧急补货建议列表
     */
    @GetMapping("/urgent/{shopId}")
    public Result<List<ReplenishmentSuggestion>> urgent(@PathVariable Long shopId) {
        if (shopId == null) {
            return Result.failure("shopId must not be null");
        }
        List<ReplenishmentSuggestion> list = replenishmentSuggestionMapper.selectList(
                new LambdaQueryWrapper<ReplenishmentSuggestion>()
                        .eq(ReplenishmentSuggestion::getShopId, shopId)
                        .eq(ReplenishmentSuggestion::getUrgencyLevel, URGENCY_URGENT));
        return Result.success(list);
    }
}
