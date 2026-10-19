package com.amz.service.impl;

import com.amz.dto.ProductMasterBatchPage;
import com.amz.mapper.AmzProductMapper;
import com.amz.model.AmzProduct;
import com.amz.service.ProductMasterService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * 搜索索引变更通知。
     * <p>
     * {@code required = false}：部分测试切片不装配 Feign 客户端；生产环境它一定存在。
     * 为空时跳过通知——不因为「没接搜索」就让商品主数据写不进去。
     */
    @Autowired(required = false)
    private com.amz.client.SearchIndexClient searchIndexClient;

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
        notifySearchIndex(product);
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
        notifySearchIndex(current);
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

    /**
     * 单页上限。与 {@link #READ_CAP} 同量级：重建是按页处理的，单页过大只会让
     * 一次 ES bulk 写放大，不能提高吞吐。
     */
    private static final int INDEX_PAGE_MAX = READ_CAP;

    @Override
    public ProductMasterBatchPage readBatchForIndex(Long afterId, Integer limit) {
        int size = limit == null || limit <= 0 ? INDEX_PAGE_MAX : Math.min(limit, INDEX_PAGE_MAX);
        LambdaQueryWrapper<AmzProduct> wrapper = new LambdaQueryWrapper<>();
        if (afterId != null) {
            wrapper.gt(AmzProduct::getId, afterId);
        }
        // 按 id 升序 keyset 分页：不受 offset 漂移影响（重建期间有新增也不会漏或重）
        wrapper.orderByAsc(AmzProduct::getId).last("LIMIT " + size);
        List<AmzProduct> rows = amzProductMapper.selectList(wrapper);

        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (AmzProduct row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            // 键名必须与 ProductMasterBatchPage.FIELD_NAMES 一致：
            // 用 Map 传输时字段名就是契约，写错不会编译报错，只会静默丢字段。
            item.put("id", row.getId());
            item.put("shopId", row.getShopId());
            item.put("sku", row.getSku());
            item.put("asin", row.getAsin());
            item.put("marketplaceId", row.getMarketplaceId());
            item.put("title", row.getTitle());
            item.put("description", row.getDescription());
            item.put("brand", row.getBrand());
            item.put("price", row.getPrice());
            item.put("currency", row.getCurrency());
            item.put("category", row.getCategory());
            item.put("productType", row.getProductType());
            item.put("sizeTier", row.getSizeTier());
            item.put("weightG", row.getWeightG());
            item.put("status", row.getStatus());
            out.add(item);
        }

        ProductMasterBatchPage page = new ProductMasterBatchPage();
        page.setRows(out);
        // 只有取满一页才可能有下一页：不满说明已经到尾部，避免多一次空查询
        boolean hasMore = rows.size() == size;
        page.setNextCursor(hasMore ? rows.get(rows.size() - 1).getId() : null);
        return page;
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

    /**
     * 把商品主数据变更通知给搜索索引。
     * <p>
     * <b>为什么在事务外/同步调用而不发 MQ</b>：调用方（controller）在 service 返回后
     * 才提交事务？——不是，本服务没有 {@code @Transactional}，{@code insert}/{@code updateById}
     * 各自自动提交。因此此处的通知发生在数据已落库之后，search 侧读不到未提交数据的问题不存在。
     * 若将来给 create/update 加事务，通知必须移到事务提交之后（否则 search 可能收到
     * 一条随后被回滚的记录，索引与事实源不一致）。
     * <p>
     * <b>失败只记 WARN，不回滚</b>：索引是派生数据。商品保存失败再让运营重试一次，
     * 与「保存成功但索引没更新、之后一次重建能补上」，后者代价更低。但 WARN 必须留痕：
     * 索引漏更新的唯一症状是「搜不到」，没有日志就只能靠猜。
     */
    private void notifySearchIndex(AmzProduct product) {
        if (searchIndexClient == null || product == null || product.getId() == null) {
            return;
        }
        try {
            java.util.Map<String, Object> doc = new java.util.LinkedHashMap<>();
            // 键名以 search 侧 /search/index/notify/upsert 的 ProductDoc 契约为准
            doc.put("id", product.getId());
            doc.put("title", product.getTitle());
            // description 在 ProductDoc 里叫 content
            doc.put("content", product.getDescription());
            doc.put("sku", product.getSku());
            doc.put("shopId", product.getShopId());
            doc.put("price", product.getPrice() == null ? null : product.getPrice().doubleValue());
            com.amz.result.Result<Boolean> result = searchIndexClient.upsert(doc);
            if (result == null || result.getCode() != 200 || !Boolean.TRUE.equals(result.getData())) {
                log.warn("商品索引通知未成功 id={}：{}（主数据已保存，索引需重建或重试）",
                        product.getId(), result == null ? "无响应" : result.getMessage());
            }
        } catch (Exception e) {
            // 通知是旁路：不能让搜索服务的抖动把商品保存变成失败
            log.warn("商品索引通知异常 id={}（主数据已保存）", product.getId(), e);
        }
    }

    private static boolean isText(String s) {
        return s != null && !s.isBlank();
    }

}
