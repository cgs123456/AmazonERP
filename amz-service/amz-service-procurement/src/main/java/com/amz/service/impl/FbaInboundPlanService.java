package com.amz.service.impl;

import com.amz.client.SpApiFbaInboundClient;
import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FBA 入库计划创建（补货建议 → Amazon 真实入库计划）。
 * <p>
 * <b>存在动因</b>：{@code fbaInbound} 的 45 条 operation 早已进统一目录与限流表，
 * 但采购侧的 FBA 货件业务层对它们零调用——补货建议算出来了，却落不成真实入库计划，
 * 「算出来 → 真的入仓」这一步此前只能人工去卖家后台做。本服务补上这条接线。
 * <p>
 * <b>请求形状依据</b>：官方快照
 * {@code contracts/fulfillmentInbound_2024-03-20.json} 的
 * {@code POST /inbound/fba/2024-03-20/inboundPlans}，
 * 必填 {@code destinationMarketplaces}（恰好 1 个）+ {@code items}（每项
 * {@code labelOwner/msku/prepOwner/quantity} 必填）+ {@code sourceAddress}。
 * 成功状态 <b>202</b>（异步受理），返回 {@code inboundPlanId}。
 * <p>
 * <b>不伪造计划</b>：spapi 不可用时返回失败，绝不生成一个本地假的 inboundPlanId——
 * 后续装箱/贴标都会指向一个 Amazon 不认识的对象，等到那一步才暴露，排查成本极高。
 * <p>
 * <b>无真实凭证下只能做到 API-ready</b>：本服务只保证请求形状与官方一致、
 * 失败显式返回；不宣称已联调（与仓库既有诚实边界一致）。
 */
@Slf4j
@Service
public class FbaInboundPlanService {

    /** 官方 operationId（限流档位 fbaInbound.createInboundPlan 2 req/s burst 2）。 */
    static final String OP_CREATE_INBOUND_PLAN = "fbaInbound.createInboundPlan";

    /** 官方 prepOwner / labelOwner 取值（SELLER 或 AMAZON）。 */
    private static final java.util.Set<String> OWNERS = java.util.Set.of("SELLER", "AMAZON");

    @Autowired(required = false)
    private SpApiFbaInboundClient spApiFbaInboundClient;

    /**
     * 由补货建议创建 Amazon 入库计划。
     *
     * @param shopId        店铺
     * @param marketplaceId 目的站点（官方要求恰好一个）
     * @param planName      计划名（可空，官方给默认名）
     * @param sourceAddress 发货地址（官方必填；不提供默认值——编造地址会让货发错地方）
     * @param lines         逐行明细（msku + quantity + prepOwner/labelOwner）
     * @return Amazon 返回的响应（含 inboundPlanId）
     */
    public Map<String, Object> createInboundPlan(Long shopId, String marketplaceId, String planName,
                                                 SourceAddress sourceAddress, List<InboundLine> lines) {
        if (shopId == null) {
            throw new CodeErrorException("shopId 不能为空");
        }
        if (!StringUtils.hasText(marketplaceId)) {
            throw new CodeErrorException("目的站点（marketplaceId）不能为空");
        }
        if (lines == null || lines.isEmpty()) {
            throw new CodeErrorException("入库计划至少需要一行明细");
        }
        validateAddress(sourceAddress);
        if (spApiFbaInboundClient == null) {
            throw new CodeErrorException("FBA 入库计划通道不可用（spapi 客户端未装配）");
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (InboundLine line : lines) {
            if (!StringUtils.hasText(line.msku())) {
                throw new CodeErrorException("明细缺少 msku");
            }
            if (line.quantity() == null || line.quantity() <= 0) {
                throw new CodeErrorException("明细数量必须大于 0：msku=" + line.msku());
            }
            String prepOwner = normalizeOwner(line.prepOwner(), "prepOwner");
            String labelOwner = normalizeOwner(line.labelOwner(), "labelOwner");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("msku", line.msku().trim());
            item.put("quantity", line.quantity());
            item.put("prepOwner", prepOwner);
            item.put("labelOwner", labelOwner);
            items.add(item);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("destinationMarketplaces", List.of(marketplaceId.trim()));
        body.put("items", items);
        if (StringUtils.hasText(planName)) {
            body.put("name", planName.trim());
        }
        body.put("sourceAddress", sourceAddress.toMap());

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("shopId", shopId);
        request.put("marketplaceId", marketplaceId.trim());
        request.put("body", body);

        log.info("创建 FBA 入库计划 shopId={} marketplace={} lines={} operator={}",
                shopId, marketplaceId, items.size(), UserContext.getUserId());

        Result<Map<String, Object>> result =
                spApiFbaInboundClient.execute(OP_CREATE_INBOUND_PLAN, request);
        if (result == null || result.getCode() != 200 || result.getData() == null) {
            String reason = result == null ? "无响应" : result.getMessage();
            // 不伪造计划 ID：宁可让调用方看到失败，也不给一个 Amazon 不认识的对象
            throw new CodeErrorException("创建 FBA 入库计划失败：" + reason);
        }
        return result.getData();
    }

    private void validateAddress(SourceAddress address) {
        if (address == null) {
            throw new CodeErrorException("发货地址（sourceAddress）必填，官方要求且无合理默认值");
        }
        if (!StringUtils.hasText(address.name())) {
            throw new CodeErrorException("发货地址缺少 name");
        }
        if (!StringUtils.hasText(address.addressLine1())) {
            throw new CodeErrorException("发货地址缺少 addressLine1");
        }
        if (!StringUtils.hasText(address.city())) {
            throw new CodeErrorException("发货地址缺少 city");
        }
        if (!StringUtils.hasText(address.countryCode()) || address.countryCode().trim().length() != 2) {
            throw new CodeErrorException("发货地址 countryCode 必须是两位 ISO 3166-1（如 US/CN）");
        }
        if (!StringUtils.hasText(address.postalCode())) {
            throw new CodeErrorException("发货地址缺少 postalCode");
        }
    }

    private String normalizeOwner(String owner, String field) {
        if (!StringUtils.hasText(owner)) {
            // 官方必填；默认 SELLER 是绝大多数自营场景，但显式声明比隐式猜更安全
            throw new CodeErrorException(field + " 必填（SELLER 或 AMAZON）");
        }
        String normalized = owner.trim().toUpperCase(java.util.Locale.ROOT);
        if (!OWNERS.contains(normalized)) {
            throw new CodeErrorException(field + " 只支持 SELLER/AMAZON，实际「" + owner + "」");
        }
        return normalized;
    }

    /** 发货地址（官方 CreateInboundPlanRequest.sourceAddress）。 */
    public record SourceAddress(String name, String addressLine1, String addressLine2,
                                String city, String districtOrCounty, String stateOrProvinceCode,
                                String countryCode, String postalCode) {

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name.trim());
            map.put("addressLine1", addressLine1.trim());
            if (StringUtils.hasText(addressLine2)) {
                map.put("addressLine2", addressLine2.trim());
            }
            map.put("city", city.trim());
            if (StringUtils.hasText(districtOrCounty)) {
                map.put("districtOrCounty", districtOrCounty.trim());
            }
            if (StringUtils.hasText(stateOrProvinceCode)) {
                map.put("stateOrProvinceCode", stateOrProvinceCode.trim());
            }
            map.put("countryCode", countryCode.trim().toUpperCase(java.util.Locale.ROOT));
            map.put("postalCode", postalCode.trim());
            return map;
        }
    }

    /** 入库计划的一行明细（对应官方 ItemInput 的必填字段）。 */
    public record InboundLine(String msku, Integer quantity, String prepOwner, String labelOwner) {
    }
}
