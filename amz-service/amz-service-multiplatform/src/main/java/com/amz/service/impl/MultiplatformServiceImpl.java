package com.amz.service.impl;

import com.amz.client.SheinClient;
import com.amz.client.TemuClient;
import com.amz.client.TikTokClient;
import com.amz.exception.AttrIsNullException;
import com.amz.finance.PlatformCurrencyConverter;
import com.amz.mapper.UnifiedOrderMapper;
import com.amz.model.UnifiedOrder;
import com.amz.service.MultiplatformService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 多平台订单聚合服务实现。
 * <p>
 * 核心职责：
 * <ol>
 *   <li>调用各平台客户端拉取订单</li>
 *   <li>归一化为 UnifiedOrder 并折算 CNY 金额</li>
 *   <li>去重落库（按 platformOrderNo 唯一）</li>
 *   <li>聚合查询：全平台列表 + 按平台筛选</li>
 *   <li>发货回传：调用对应平台 markShipped</li>
 * </ol>
 */
@Slf4j
@Service
public class MultiplatformServiceImpl implements MultiplatformService {

    @Autowired
    private UnifiedOrderMapper unifiedOrderMapper;

    @Autowired
    private TemuClient temuClient;

    @Autowired
    private TikTokClient tiktokClient;

    @Autowired
    private SheinClient sheinClient;

    @Autowired
    private PlatformCurrencyConverter currencyConverter;

    @Override
    public int syncAllPlatforms(Long shopId) {
        int temu = syncByPlatform(shopId, "TEMU");
        int tiktok = syncByPlatform(shopId, "TIKTOK");
        int shein = syncByPlatform(shopId, "SHEIN");
        log.info("多平台订单同步完成 shopId={}：Temu={} TikTok={} Shein={}", shopId, temu, tiktok, shein);
        return temu + tiktok + shein;
    }

    @Override
    public int syncByPlatform(Long shopId, String platform) {
        List<UnifiedOrder> fetched;
        switch (platform) {
            case "TEMU":
                fetched = temuClient.fetchRecentOrders(shopId);
                break;
            case "TIKTOK":
                fetched = tiktokClient.fetchRecentOrders(shopId);
                break;
            case "SHEIN":
                fetched = sheinClient.fetchRecentOrders(shopId);
                break;
            default:
                throw new AttrIsNullException("不支持的平台：" + platform);
        }

        int inserted = 0;
        for (UnifiedOrder o : fetched) {
            // 去重：同 platformOrderNo 已存在则跳过
            LambdaQueryWrapper<UnifiedOrder> dedup = new LambdaQueryWrapper<>();
            dedup.eq(UnifiedOrder::getPlatform, o.getPlatform())
                 .eq(UnifiedOrder::getPlatformOrderNo, o.getPlatformOrderNo());
            if (unifiedOrderMapper.selectCount(dedup) > 0) {
                continue;
            }
            // 生成统一订单号 + 折算 CNY
            o.setUnifiedOrderNo("UO" + System.currentTimeMillis() + inserted);
            o.setCnyAmount(currencyConverter.toCny(o.getOriginalAmount(), o.getCurrency()));
            unifiedOrderMapper.insert(o);
            inserted++;
        }
        return inserted;
    }

    @Override
    public List<UnifiedOrder> listOrders(Long shopId) {
        LambdaQueryWrapper<UnifiedOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UnifiedOrder::getShopId, shopId)
               .orderByDesc(UnifiedOrder::getId);
        return unifiedOrderMapper.selectList(wrapper);
    }

    @Override
    public List<UnifiedOrder> listByPlatform(Long shopId, String platform) {
        LambdaQueryWrapper<UnifiedOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UnifiedOrder::getShopId, shopId)
               .eq(UnifiedOrder::getPlatform, platform)
               .orderByDesc(UnifiedOrder::getId);
        return unifiedOrderMapper.selectList(wrapper);
    }

    @Override
    public boolean markShipped(Long orderId, String trackingNo) {
        UnifiedOrder order = unifiedOrderMapper.selectById(orderId);
        if (order == null) {
            throw new AttrIsNullException("订单不存在：id=" + orderId);
        }
        boolean ok;
        switch (order.getPlatform()) {
            case "TEMU":
                ok = temuClient.markShipped(order.getPlatformOrderNo(), trackingNo);
                break;
            case "TIKTOK":
                ok = tiktokClient.markShipped(order.getPlatformOrderNo(), trackingNo);
                break;
            case "SHEIN":
                ok = sheinClient.markShipped(order.getPlatformOrderNo(), trackingNo);
                break;
            default:
                throw new AttrIsNullException("不支持的平台：" + order.getPlatform());
        }
        if (ok) {
            order.setTrackingNo(trackingNo);
            order.setStatus("SHIPPED");
            unifiedOrderMapper.updateById(order);
        }
        return ok;
    }
}
