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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        // 每个平台独立 try-catch，单平台故障不影响其他平台同步（降级处理）
        int temu = syncPlatformSafely(shopId, "TEMU");
        int tiktok = syncPlatformSafely(shopId, "TIKTOK");
        int shein = syncPlatformSafely(shopId, "SHEIN");
        log.info("多平台订单同步完成 shopId={}：Temu={} TikTok={} Shein={}", shopId, temu, tiktok, shein);
        return temu + tiktok + shein;
    }

    /**
     * 单平台同步降级封装：出现异常时记录日志并返回 0，不向上抛出。
     * <p>
     * 修复：原 syncAllPlatforms 直接串行调用 syncByPlatform，
     * 任一平台 API 异常会导致后续平台无法同步。
     */
    private int syncPlatformSafely(Long shopId, String platform) {
        try {
            return syncByPlatform(shopId, platform);
        } catch (Exception e) {
            log.error("多平台同步降级：shopId={} platform={} 同步失败，跳过该平台继续后续同步",
                    shopId, platform, e);
            return 0;
        }
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

        // 批量去重：一次性查询本批次所有 platformOrderNo 已存在的记录，
        // 避免循环内逐条 selectCount 导致 N+1 查询。
        Set<String> existingKeys = loadExistingOrderKeys(fetched);

        int inserted = 0;
        for (UnifiedOrder o : fetched) {
            // 内存去重：同 platform + platformOrderNo 已存在则跳过
            String key = dedupKey(o.getPlatform(), o.getPlatformOrderNo());
            if (key != null && existingKeys.contains(key)) {
                continue;
            }
            // 生成统一订单号 + 折算 CNY
            o.setUnifiedOrderNo("UO" + System.currentTimeMillis() + inserted);
            o.setCnyAmount(currencyConverter.toCny(o.getOriginalAmount(), o.getCurrency()));
            unifiedOrderMapper.insert(o);
            // 插入后加入集合，防止同批次内重复订单号重复入库
            if (key != null) {
                existingKeys.add(key);
            }
            inserted++;
        }
        return inserted;
    }

    /**
     * 批量加载已存在订单的 (platform + platformOrderNo) 组合键集合。
     * <p>
     * 修复：原实现循环内逐条 selectCount，N 条订单 N 次 SQL。
     * 现改为单条 SQL：select platform_order_no from amz_unified_order
     * where platform_order_no in (...)，再在内存组装去重键。
     */
    private Set<String> loadExistingOrderKeys(List<UnifiedOrder> fetched) {
        Set<String> existingKeys = new HashSet<>();
        if (fetched == null || fetched.isEmpty()) {
            return existingKeys;
        }
        List<String> orderNoList = new ArrayList<>();
        for (UnifiedOrder o : fetched) {
            if (o.getPlatformOrderNo() != null && !o.getPlatformOrderNo().isEmpty()) {
                orderNoList.add(o.getPlatformOrderNo());
            }
        }
        if (orderNoList.isEmpty()) {
            return existingKeys;
        }
        LambdaQueryWrapper<UnifiedOrder> dedupQuery = new LambdaQueryWrapper<>();
        dedupQuery.in(UnifiedOrder::getPlatformOrderNo, orderNoList);
        for (UnifiedOrder exist : unifiedOrderMapper.selectList(dedupQuery)) {
            existingKeys.add(dedupKey(exist.getPlatform(), exist.getPlatformOrderNo()));
        }
        return existingKeys;
    }

    private String dedupKey(String platform, String platformOrderNo) {
        if (platformOrderNo == null || platformOrderNo.isEmpty()) {
            return null;
        }
        return platform + "|" + platformOrderNo;
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
