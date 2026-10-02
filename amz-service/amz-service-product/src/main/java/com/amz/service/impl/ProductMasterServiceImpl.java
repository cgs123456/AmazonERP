package com.amz.service.impl;

import com.amz.mapper.AmzProductMapper;
import com.amz.model.AmzProduct;
import com.amz.service.ProductMasterService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品主数据维护实现。
 * <p>
 * 约束以真实 DDL 为准（{@code V1__init.sql:42-61} + {@code V2__listing_product_type.sql}）：
 * 唯一键 {@code uk_shop_sku_market (shop_id, sku, marketplace_id)}，
 * 而 {@code marketplace_id} 与 {@code title} 都是 <b>NOT NULL</b>——所以必须在这里挡掉空值，
 * 否则到 MySQL 才报错，运维看到的是不可读的约束异常；
 * 表里<b>没有 update_time 列</b>，因此更新时不去设置它（不凭想象加字段）。
 * 列表一律带上限，不做无界整表读。
 */
@Slf4j
@Service
public class ProductMasterServiceImpl implements ProductMasterService {

    /** 单次返回上限；与 PageRequest.MAX_SIZE 同量级，避免大店整表拉进内存。 */
    static final int READ_CAP = 500;

    @Autowired
    private AmzProductMapper amzProductMapper;

    @Override
    public List<AmzProduct> list(Long shopId, String asin, String keyword) {
        if (shopId == null) {
            throw new IllegalArgumentException("shopId 不能为空");
        }
        LambdaQueryWrapper<AmzProduct> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AmzProduct::getShopId, shopId);
        if (isText(asin)) {
            wrapper.eq(AmzProduct::getAsin, asin.trim());
        }
        if (isText(keyword)) {
            String k = keyword.trim();
            wrapper.and(w -> w.like(AmzProduct::getTitle, k)
                              .or().like(AmzProduct::getSku, k)
                              .or().like(AmzProduct::getAsin, k));
        }
        wrapper.orderByAsc(AmzProduct::getSku).last("LIMIT " + READ_CAP);
        return amzProductMapper.selectList(wrapper);
    }

    @Override
    public AmzProduct get(Long shopId, Long id) {
        AmzProduct row = requireOwned(shopId, id);
        return row;
    }

    @Override
    public AmzProduct create(AmzProduct product) {
        if (product == null || product.getShopId() == null) {
            throw new IllegalArgumentException("shopId 不能为空");
        }
        if (!isText(product.getSku())) {
            throw new IllegalArgumentException("sku 不能为空（主数据的业务标识）");
        }
        // DDL 上 marketplace_id / title 都是 NOT NULL：在这里说清楚，别让 MySQL 去报错
        if (!isText(product.getMarketplaceId())) {
            throw new IllegalArgumentException("marketplaceId 不能为空（唯一键成员，DDL 为 NOT NULL）");
        }
        if (!isText(product.getTitle())) {
            throw new IllegalArgumentException("title 不能为空（DDL 为 NOT NULL）");
        }
        product.setId(null);
        product.setSku(product.getSku().trim());
        if (existsSameKey(product.getShopId(), product.getSku(), product.getMarketplaceId().trim(), null)) {
            throw new IllegalArgumentException("同一店铺下该 SKU+站点已存在："
                    + product.getShopId() + "/" + product.getSku() + "/" + product.getMarketplaceId());
        }
        if (!isText(product.getStatus())) {
            product.setStatus("ACTIVE");
        }
        product.setCreateTime(LocalDateTime.now());
        amzProductMapper.insert(product);
        log.info("新增商品主数据：shopId={} sku={} asin={} id={}",
                product.getShopId(), product.getSku(), product.getAsin(), product.getId());
        return product;
    }

    @Override
    public AmzProduct update(Long shopId, Long id, AmzProduct patch) {
        AmzProduct current = requireOwned(shopId, id);
        if (patch == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String newSku = isText(patch.getSku()) ? patch.getSku().trim() : current.getSku();
        String newMarketplace = isText(patch.getMarketplaceId())
                ? patch.getMarketplaceId().trim() : current.getMarketplaceId();
        // 历史行可能没有 marketplace（旧 Product 实体不带这一列），比较要容纳 null
        boolean keyChanged = !newSku.equals(current.getSku())
                || !(newMarketplace == null ? current.getMarketplaceId() == null
                                            : newMarketplace.equals(current.getMarketplaceId()));
        if (keyChanged && newMarketplace != null) {
            if (existsSameKey(current.getShopId(), newSku, newMarketplace, current.getId())) {
                throw new IllegalArgumentException("改后的 SKU+站点与另一条主数据冲突：" + newSku
                        + "/" + newMarketplace);
            }
        }
        // 只覆盖显式给出的字段：id / shopId / createTime 不允许被请求体改写
        current.setSku(newSku);
        if (isText(patch.getAsin())) current.setAsin(patch.getAsin().trim());
        if (isText(patch.getMarketplaceId())) current.setMarketplaceId(patch.getMarketplaceId());
        if (isText(patch.getTitle())) current.setTitle(patch.getTitle());
        if (patch.getDescription() != null) current.setDescription(patch.getDescription());
        if (isText(patch.getBrand())) current.setBrand(patch.getBrand());
        if (patch.getPrice() != null) current.setPrice(patch.getPrice());
        if (isText(patch.getCurrency())) current.setCurrency(patch.getCurrency());
        if (isText(patch.getCategory())) current.setCategory(patch.getCategory());
        if (isText(patch.getProductType())) current.setProductType(patch.getProductType());
        if (isText(patch.getSizeTier())) current.setSizeTier(patch.getSizeTier());
        if (patch.getWeightG() != null) current.setWeightG(patch.getWeightG());
        if (isText(patch.getStatus())) current.setStatus(patch.getStatus());
        amzProductMapper.updateById(current);
        return current;
    }

    @Override
    public List<AmzProduct> listByAsinAcrossShops(List<Long> shopIds, String asin) {
        if (shopIds == null || shopIds.isEmpty()) {
            throw new IllegalArgumentException("缺少可访问的店铺范围");
        }
        if (!isText(asin)) {
            throw new IllegalArgumentException("asin 不能为空");
        }
        LambdaQueryWrapper<AmzProduct> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(AmzProduct::getShopId, shopIds)
               .eq(AmzProduct::getAsin, asin.trim())
               .orderByAsc(AmzProduct::getShopId)
               .last("LIMIT " + READ_CAP);
        return amzProductMapper.selectList(wrapper);
    }

    private AmzProduct requireOwned(Long shopId, Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id 不能为空");
        }
        AmzProduct row = amzProductMapper.selectById(id);
        if (row == null) {
            throw new IllegalArgumentException("主数据不存在：id=" + id);
        }
        if (shopId != null && !shopId.equals(row.getShopId())) {
            // 不返回他人店铺的行，也不透露它存在
            throw new IllegalArgumentException("主数据不存在：id=" + id);
        }
        return row;
    }

    /** 唯一键存在性检查：三个成员列都是 NOT NULL，直接等值比较。 */
    private boolean existsSameKey(Long shopId, String sku, String marketplaceId, Long excludeId) {
        LambdaQueryWrapper<AmzProduct> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AmzProduct::getShopId, shopId)
               .eq(AmzProduct::getSku, sku)
               .eq(AmzProduct::getMarketplaceId, marketplaceId);
        if (excludeId != null) {
            wrapper.ne(AmzProduct::getId, excludeId);
        }
        return amzProductMapper.selectCount(wrapper) > 0;
    }

    private static boolean isText(String s) {
        return s != null && !s.isBlank();
    }

}
