package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.exception.InvalidParamException;
import com.amz.util.BizNoGenerator;

import com.amz.exception.AttrIsNullException;
import com.amz.mapper.InventoryBatchMapper;
import com.amz.mapper.PurchaseOrderMapper;
import com.amz.mapper.SupplierMapper;
import com.amz.mapper.SupplierProductMapper;
import com.amz.model.Supplier;
import com.amz.model.SupplierProduct;
import com.amz.model.PurchaseOrder;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.service.SupplierService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        requireShopAccess(supplier.getShopId());
        if (supplier.getSupplierCode() == null || supplier.getSupplierCode().isBlank()) {
            supplier.setSupplierCode(BizNoGenerator.next("SUP-"));
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
        if (supplier == null || supplier.getId() == null) {
            throw new AttrIsNullException("供应商ID不能为空");
        }
        Supplier existing = requireSupplierAccess(supplier.getId());
        if (supplier.getShopId() != null && !Objects.equals(supplier.getShopId(), existing.getShopId())) {
            throw new CodeErrorException("供应商所属店铺不可修改");
        }
        supplier.setShopId(existing.getShopId());
        supplierMapper.updateById(supplier);
        return supplier;
    }

    @Override
    public PageResult<Supplier> listSuppliers(Long shopId, String status, String keyword, PageRequest page) {
        requireShopAccess(shopId);
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        LambdaQueryWrapper<Supplier> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Supplier::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(Supplier::getStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            String term = keyword.trim();
            wrapper.and(w -> w.like(Supplier::getSupplierName, term)
                    .or().like(Supplier::getSupplierCode, term)
                    .or().like(Supplier::getContactName, term));
        }
        SupplierCursor cursor = parseSupplierCursor(req);
        if (cursor != null) {
            if (cursor.rating() == null) {
                wrapper.isNull(Supplier::getRating)
                        .lt(Supplier::getId, cursor.id());
            } else {
                wrapper.and(w -> w.lt(Supplier::getRating, cursor.rating())
                        .or(n -> n.eq(Supplier::getRating, cursor.rating())
                                .lt(Supplier::getId, cursor.id()))
                        .or().isNull(Supplier::getRating));
            }
        }
        wrapper.orderByDesc(Supplier::getRating)
                .orderByDesc(Supplier::getId)
                .last("LIMIT " + req.probeSize());
        List<Supplier> rows = supplierMapper.selectList(wrapper);
        if (rows.size() > req.size()) {
            log.warn("供应商列表被截断：shopId={} size={}，调用方需携带 nextCursor 继续翻页",
                    shopId, req.size());
        }
        return PageResult.of(rows, req.size(), this::supplierCursor);
    }

    @Override
    public Supplier getSupplier(Long id) {
        return requireSupplierAccess(id);
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
        requireShopAccess(sp.getShopId());
        Supplier supplier = requireSupplierAccess(sp.getSupplierId());
        if (!Objects.equals(supplier.getShopId(), sp.getShopId())) {
            throw new CodeErrorException("供应商与店铺不匹配");
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
        requireShopAccess(shopId);
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
    private SupplierCursor parseSupplierCursor(PageRequest page) {
        if (!page.hasCursor()) {
            return null;
        }
        String[] parts = page.payload().split("\\|", -1);
        if (parts.length != 2) {
            throw new InvalidParamException("供应商游标非法：" + page.payload());
        }
        try {
            Long id = Long.parseLong(parts[1]);
            if (id <= 0) {
                throw new InvalidParamException("供应商游标 id 必须 > 0：" + page.payload());
            }
            BigDecimal rating = "NULL".equals(parts[0]) ? null : new BigDecimal(parts[0]);
            return new SupplierCursor(rating, id);
        } catch (NumberFormatException e) {
            throw new InvalidParamException("供应商游标无法解析：" + page.payload());
        }
    }

    private String supplierCursor(Supplier supplier) {
        String rating = supplier.getRating() == null
                ? "NULL"
                : supplier.getRating().stripTrailingZeros().toPlainString();
        return PageRequest.encodeCursor(rating + "|" + supplier.getId());
    }

    private record SupplierCursor(BigDecimal rating, Long id) {
    }

    private void requireShopAccess(Long shopId) {
        if (shopId == null) {
            throw new AttrIsNullException("店铺ID不能为空");
        }
        if (!UserContext.isShopAllowed(shopId)) {
            log.warn("供应商店铺越权访问拦截：userId={}, shopId={}", UserContext.getUserId(), shopId);
            throw new CodeErrorException("店铺不存在或无权访问");
        }
    }

    private Supplier requireSupplierAccess(Long supplierId) {
        if (supplierId == null) {
            throw new AttrIsNullException("供应商ID不能为空");
        }
        Supplier supplier = supplierMapper.selectById(supplierId);
        if (supplier == null || !UserContext.isShopAllowed(supplier.getShopId())) {
            log.warn("供应商越权访问拦截：userId={}, supplierId={}", UserContext.getUserId(), supplierId);
            throw new CodeErrorException("供应商不存在或无权访问");
        }
        return supplier;
    }
}
