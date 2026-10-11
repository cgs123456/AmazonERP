package com.amz.controller;

import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.result.Result;
import com.amz.service.impl.FbaInboundPlanService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * FBA 入库计划接口（补货建议 → Amazon 真实入库计划）。
 * <p>
 * 补齐此前缺失的接线：{@code fbaInbound} 的 45 条 operation 早已就绪，但采购侧零调用，
 * 「补货建议算出来 → 真的入仓」只能人工去卖家后台做。
 * <p>
 * <b>无真实凭证下只能做到 API-ready</b>：请求形状按官方快照实现、失败显式返回，
 * 不宣称已联调（与仓库既有诚实边界一致）。
 */
@Slf4j
@RestController
@RequestMapping("/procurement/fba/inbound-plan")
public class FbaInboundPlanController {

    @Autowired
    private FbaInboundPlanService fbaInboundPlanService;

    /**
     * 由补货建议创建 Amazon 入库计划。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "FBA入库", action = "创建", description = "由补货建议创建 Amazon 入库计划")
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody CreateInboundPlanRequest request) {
        try {
            List<FbaInboundPlanService.InboundLine> lines = new ArrayList<>();
            if (request.getItems() != null) {
                for (InboundItemDto dto : request.getItems()) {
                    if (dto != null) {
                        lines.add(new FbaInboundPlanService.InboundLine(
                                dto.getMsku(), dto.getQuantity(), dto.getPrepOwner(), dto.getLabelOwner()));
                    }
                }
            }
            return Result.success(fbaInboundPlanService.createInboundPlan(
                    request.getShopId(),
                    request.getMarketplaceId(),
                    request.getName(),
                    request.getSourceAddress() == null ? null : request.getSourceAddress().toRecord(),
                    lines));
        } catch (Exception e) {
            log.warn("创建 FBA 入库计划失败 shopId={}: {}", request == null ? null : request.getShopId(), e.getMessage());
            return Result.failure(e.getMessage());
        }
    }

    /** 创建入库计划请求体。 */
    public static class CreateInboundPlanRequest {
        private Long shopId;
        private String marketplaceId;
        private String name;
        private AddressDto sourceAddress;
        private List<InboundItemDto> items;

        public Long getShopId() {
            return shopId;
        }

        public void setShopId(Long shopId) {
            this.shopId = shopId;
        }

        public String getMarketplaceId() {
            return marketplaceId;
        }

        public void setMarketplaceId(String marketplaceId) {
            this.marketplaceId = marketplaceId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public AddressDto getSourceAddress() {
            return sourceAddress;
        }

        public void setSourceAddress(AddressDto sourceAddress) {
            this.sourceAddress = sourceAddress;
        }

        public List<InboundItemDto> getItems() {
            return items;
        }

        public void setItems(List<InboundItemDto> items) {
            this.items = items;
        }
    }

    /** 发货地址 DTO。 */
    public static class AddressDto {
        private String name;
        private String addressLine1;
        private String addressLine2;
        private String city;
        private String districtOrCounty;
        private String stateOrProvinceCode;
        private String countryCode;
        private String postalCode;

        FbaInboundPlanService.SourceAddress toRecord() {
            return new FbaInboundPlanService.SourceAddress(name, addressLine1, addressLine2,
                    city, districtOrCounty, stateOrProvinceCode, countryCode, postalCode);
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAddressLine1() {
            return addressLine1;
        }

        public void setAddressLine1(String addressLine1) {
            this.addressLine1 = addressLine1;
        }

        public String getAddressLine2() {
            return addressLine2;
        }

        public void setAddressLine2(String addressLine2) {
            this.addressLine2 = addressLine2;
        }

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }

        public String getDistrictOrCounty() {
            return districtOrCounty;
        }

        public void setDistrictOrCounty(String districtOrCounty) {
            this.districtOrCounty = districtOrCounty;
        }

        public String getStateOrProvinceCode() {
            return stateOrProvinceCode;
        }

        public void setStateOrProvinceCode(String stateOrProvinceCode) {
            this.stateOrProvinceCode = stateOrProvinceCode;
        }

        public String getCountryCode() {
            return countryCode;
        }

        public void setCountryCode(String countryCode) {
            this.countryCode = countryCode;
        }

        public String getPostalCode() {
            return postalCode;
        }

        public void setPostalCode(String postalCode) {
            this.postalCode = postalCode;
        }
    }

    /** 入库明细 DTO。 */
    public static class InboundItemDto {
        private String msku;
        private Integer quantity;
        private String prepOwner;
        private String labelOwner;

        public String getMsku() {
            return msku;
        }

        public void setMsku(String msku) {
            this.msku = msku;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }

        public String getPrepOwner() {
            return prepOwner;
        }

        public void setPrepOwner(String prepOwner) {
            this.prepOwner = prepOwner;
        }

        public String getLabelOwner() {
            return labelOwner;
        }

        public void setLabelOwner(String labelOwner) {
            this.labelOwner = labelOwner;
        }
    }
}
