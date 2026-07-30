package com.amz.service.impl;

import com.amz.mapper.AdCampaignExtMapper;
import com.amz.model.AdCampaignExt;
import com.amz.service.AdReportExtService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 广告综合报表服务实现（SP + SB + SD + DSP 汇总）。
 */
@Service
public class AdReportExtServiceImpl implements AdReportExtService {

    @Autowired
    private AdCampaignExtMapper campaignExtMapper;

    @Override
    public List<AdCampaignExt> listReports(Long shopId, String adType) {
        LambdaQueryWrapper<AdCampaignExt> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdCampaignExt::getShopId, shopId);
        if (adType != null && !adType.isBlank()) {
            wrapper.eq(AdCampaignExt::getAdType, adType);
        }
        wrapper.orderByDesc(AdCampaignExt::getSpend);
        return campaignExtMapper.selectList(wrapper);
    }

    @Override
    public Map<String, Object> getShopSummary(Long shopId) {
        List<AdCampaignExt> all = listReports(shopId, null);
        return aggregate(all);
    }

    @Override
    public Map<String, Map<String, Object>> getSummaryByType(Long shopId) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String type : new String[]{"SP", "SB", "SD", "DSP"}) {
            List<AdCampaignExt> typeList = listReports(shopId, type);
            result.put(type, aggregate(typeList));
        }
        return result;
    }

    private Map<String, Object> aggregate(List<AdCampaignExt> list) {
        Map<String, Object> m = new HashMap<>();
        long impressions = 0, clicks = 0;
        BigDecimal spend = BigDecimal.ZERO;
        BigDecimal sales = BigDecimal.ZERO;
        int orders = 0;
        for (AdCampaignExt c : list) {
            impressions += c.getImpressions() == null ? 0 : c.getImpressions();
            clicks += c.getClicks() == null ? 0 : c.getClicks();
            spend = spend.add(c.getSpend() == null ? BigDecimal.ZERO : c.getSpend());
            sales = sales.add(c.getSales() == null ? BigDecimal.ZERO : c.getSales());
            orders += c.getOrders() == null ? 0 : c.getOrders();
        }
        m.put("impressions", impressions);
        m.put("clicks", clicks);
        m.put("spend", spend);
        m.put("sales", sales);
        m.put("orders", orders);
        BigDecimal acos = sales.compareTo(BigDecimal.ZERO) > 0
                ? spend.divide(sales, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal roas = spend.compareTo(BigDecimal.ZERO) > 0
                ? sales.divide(spend, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        m.put("acos", acos);
        m.put("roas", roas);
        return m;
    }
}
