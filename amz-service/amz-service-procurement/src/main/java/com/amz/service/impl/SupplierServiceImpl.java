package com.amz.service.impl;

import com.amz.exception.AttrIsNullException;
import com.amz.mapper.InventoryBatchMapper;
import com.amz.mapper.PurchaseOrderMapper;
import com.amz.mapper.SupplierMapper;
import com.amz.mapper.SupplierProductMapper;
import com.amz.model.Supplier;
import com.amz.model.SupplierProduct;
import com.amz.model.PurchaseOrder;
import com.amz.service.SupplierService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 供应商管理服务实现。
 */
@Slf4j
@Service
public class SupplierServiceImpl implements SupplierService {

    @Autowired
    private SupplierMapper supplierMapper;

    @Autowired
    private SupplierProductMapper supplierProductMapper;

    @Autowired
    private PurchaseOrderMapper purchaseOrderMapper;

    @Autowired
    private InventoryBatchMapper inventoryBatchMapper;

    @Override
    public Supplier createSupplier(Supplier supplier) {
        if (supplier.getShopId() == null || supplier.getSupplierName() == null) {
            throw new AttrIsNullException("店铺ID和供应商名称不能为空");
        }
        if (supplier.getSupplierCode() == null || supplier.getSupplierCode().isBlank()) {
            supplier.setSupplierCode("SUP-" + System.currentTimeMillis());
        }
        if (supplier.getStatus() == null) {
            supplier.setStatus("ACTIVE");
        }
        if (supplier.getRating() == null) {
            supplier.setRating(BigDecimal.ZERO);
        }
        if (supplier.getOnTimeDeliveryRate() == null) {
            supplier.setOnTimeDeliveryRate(BigDecimal.ZERO);
        }
        if (supplier.getQualityPassRate() == null) {
            supplier.setQualityPassRate(BigDecimal.ZERO);
        }
        if (supplier.getTotalOrders() == null) {
            supplier.setTotalOrders(0);
        }
        if (supplier.getTotalAmount() == null) {
            supplier.setTotalAmount(BigDecimal.ZERO);
        }
        supplierMapper.insert(supplier);
        return supplier;
    }

    @Override
    public Supplier updateSupplier(Supplier supplier) {
        if (supplier.getId() == null) {
            throw new AttrIsNullException("供应商ID不能为空");
        }
        supplierMapper.updateById(supplier);
        return supplier;
    }

    @Override
    public List<Supplier> listSuppliers(Long shopId, String status) {
        LambdaQueryWrapper<Supplier> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Supplier::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(Supplier::getStatus, status);
        }
        wrapper.orderByDesc(Supplier::getRating);
        return supplierMapper.selectList(wrapper);
    }

    @Override
    public Supplier getSupplier(Long id) {
        Supplier supplier = supplierMapper.selectById(id);
        if (supplier == null) {
            throw new AttrIsNullException("供应商不存在：id=" + id);
        }
        return supplier;
    }

    @Override
    public boolean updateSupplierStatus(Long id, String status) {
        Supplier supplier = getSupplier(id);
        supplier.setStatus(status);
        supplierMapper.updateById(supplier);
        return true;
    }

    @Override
    public SupplierProduct addSupplierProduct(SupplierProduct sp) {
        if (sp.getSupplierId() == null || sp.getShopId() == null || sp.getSku() == null) {
            throw new AttrIsNullException("供应商ID、店铺ID和SKU不能为空");
        }
        if (sp.getMoq() == null) sp.setMoq(1);
        if (sp.getLeadTimeDays() == null) sp.setLeadTimeDays(7);
        if (sp.getIsPreferred() == null) sp.setIsPreferred(0);
        if (sp.getStatus() == null) sp.setStatus("ACTIVE");

        // 如果设为首选，先取消同SKU的其他首选
        if (sp.getIsPreferred() == 1) {
            LambdaQueryWrapper<SupplierProduct> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SupplierProduct::getShopId, sp.getShopId())
                   .eq(SupplierProduct::getSku, sp.getSku())
                   .eq(SupplierProduct::getIsPreferred, 1);
            List<SupplierProduct> existing = supplierProductMapper.selectList(wrapper);
            for (SupplierProduct existingSp : existing) {
                existingSp.setIsPreferred(0);
                supplierProductMapper.updateById(existingSp);
            }
        }
        supplierProductMapper.insert(sp);
        return sp;
    }

    @Override
    public List<SupplierProduct> findSuppliersBySku(Long shopId, String sku) {
        LambdaQueryWrapper<SupplierProduct> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SupplierProduct::getShopId, shopId)
               .eq(SupplierProduct::getSku, sku)
               .eq(SupplierProduct::getStatus, "ACTIVE")
               .orderByDesc(SupplierProduct::getIsPreferred)
               .orderByAsc(SupplierProduct::getSupplyPrice);
        return supplierProductMapper.selectList(wrapper);
    }

    @Override
    public List<Map<String, Object>> compareSupplierPrices(Long shopId, String sku) {
        List<SupplierProduct> products = findSuppliersBySku(shopId, sku);
        return products.stream().map(sp -> {
            Map<String, Object> item = new LinkedHashMap<>();
            Supplier supplier = supplierMapper.selectById(sp.getSupplierId());
            item.put("supplierId", sp.getSupplierId());
            item.put("supplierName", supplier != null ? supplier.getSupplierName() : "未知");
            item.put("supplierRating", supplier != null ? supplier.getRating() : BigDecimal.ZERO);
            item.put("supplyPrice", sp.getSupplyPrice());
            item.put("moq", sp.getMoq());
            item.put("leadTimeDays", sp.getLeadTimeDays());
            item.put("isPreferred", sp.getIsPreferred() == 1);
            // 计算综合性价比评分：价格越低分越高，交期越短分越高
            BigDecimal priceScore = sp.getSupplyPrice().compareTo(BigDecimal.ZERO) > 0
                    ? BigDecimal.valueOf(100).divide(sp.getSupplyPrice(), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal leadTimeScore = BigDecimal.valueOf(100)
                    .divide(BigDecimal.valueOf(sp.getLeadTimeDays() + 1), 2, RoundingMode.HALF_UP);
            BigDecimal overallScore = priceScore.multiply(new BigDecimal("0.6"))
                    .add(leadTimeScore.multiply(new BigDecimal("0.4")));
            item.put("overallScore", overallScore);
            return item;
        }).collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> calculateSupplierKpi(Long supplierId) {
        Supplier supplier = getSupplier(supplierId);
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("supplierId", supplierId);
        kpi.put("supplierName", supplier.getSupplierName());
        kpi.put("rating", supplier.getRating());
        kpi.put("onTimeDeliveryRate", supplier.getOnTimeDeliveryRate());
        kpi.put("qualityPassRate", supplier.getQualityPassRate());
        kpi.put("priceCompetitiveness", supplier.getPriceCompetitiveness());
        kpi.put("responseSpeed", supplier.getResponseSpeed());
        kpi.put("totalOrders", supplier.getTotalOrders());
        kpi.put("totalAmount", supplier.getTotalAmount());

        // 综合评级：S/A/B/C/D
        BigDecimal composite = supplier.getRating();
        if (supplier.getOnTimeDeliveryRate() != null && supplier.getQualityPassRate() != null) {
            composite = supplier.getOnTimeDeliveryRate()
                    .add(supplier.getQualityPassRate())
                    .divide(new BigDecimal("40"), 2, RoundingMode.HALF_UP);
        }
        String grade;
        if (composite.compareTo(new BigDecimal("4.5")) >= 0) grade = "S";
        else if (composite.compareTo(new BigDecimal("4.0")) >= 0) grade = "A";
        else if (composite.compareTo(new BigDecimal("3.5")) >= 0) grade = "B";
        else if (composite.compareTo(new BigDecimal("3.0")) >= 0) grade = "C";
        else grade = "D";
        kpi.put("compositeScore", composite);
        kpi.put("grade", grade);

        return kpi;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateSupplierStats(Long supplierId, BigDecimal orderAmount, boolean onTime, boolean qualityPass) {
        Supplier supplier = getSupplier(supplierId);
        // 更新订单数和金额
        supplier.setTotalOrders((supplier.getTotalOrders() == null ? 0 : supplier.getTotalOrders()) + 1);
        supplier.setTotalAmount((supplier.getTotalAmount() == null ? BigDecimal.ZERO : supplier.getTotalAmount())
                .add(orderAmount));

        // 增量更新准时交货率和质量合格率（简单移动平均）
        int totalOrders = supplier.getTotalOrders();
        BigDecimal prevOnTime = supplier.getOnTimeDeliveryRate() == null ? BigDecimal.ZERO : supplier.getOnTimeDeliveryRate();
        BigDecimal prevQuality = supplier.getQualityPassRate() == null ? BigDecimal.ZERO : supplier.getQualityPassRate();

        BigDecimal newOnTime = prevOnTime.multiply(BigDecimal.valueOf(totalOrders - 1))
                .add(BigDecimal.valueOf(onTime ? 100 : 0))
                .divide(BigDecimal.valueOf(totalOrders), 2, RoundingMode.HALF_UP);
        BigDecimal newQuality = prevQuality.multiply(BigDecimal.valueOf(totalOrders - 1))
                .add(BigDecimal.valueOf(qualityPass ? 100 : 0))
                .divide(BigDecimal.valueOf(totalOrders), 2, RoundingMode.HALF_UP);

        supplier.setOnTimeDeliveryRate(newOnTime);
        supplier.setQualityPassRate(newQuality);

        // 重新计算综合评分
        BigDecimal rating = newOnTime.multiply(new BigDecimal("0.4"))
                .add(newQuality.multiply(new BigDecimal("0.4")))
                .divide(new BigDecimal("20"), 1, RoundingMode.HALF_UP);
        if (supplier.getPriceCompetitiveness() != null) {
            rating = rating.add(supplier.getPriceCompetitiveness().multiply(new BigDecimal("0.1")));
        }
        if (supplier.getResponseSpeed() != null) {
            rating = rating.add(supplier.getResponseSpeed().multiply(new BigDecimal("0.1")));
        }
        supplier.setRating(rating.setScale(1, RoundingMode.HALF_UP));

        supplierMapper.updateById(supplier);
    }
}
