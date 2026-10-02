package com.amz.service;

import com.amz.model.AmzProduct;

import java.util.List;


/**
 * 商品主数据（amz_product）的维护契约。
 * <p>
 * 存在动因（2026-10-02 功能覆盖清点）：同一个表 {@code amz_product} 上并存的
 * {@code model/pojo/Product} 已被标 {@code @Deprecated} 且只有 12 个字段，
 * 而真正承载 FBA 计费与跨站复制所需信息的 {@link AmzProduct}（brand/sizeTier/weightG/marketplaceId）
 * 此前**只被 ListingCopyService 读**，没有任何写入与查询入口——于是费用试算只能靠请求参数临时传，
 * 跨店铺的同一 ASIN 映射也无从查起。
 */
public interface ProductMasterService {

    /** 按店铺列主数据，可选按 ASIN / 关键词（标题或 SKU）过滤。 */
    List<AmzProduct> list(Long shopId, String asin, String keyword);

    /** 单条详情（含店铺隔离校验）。 */
    AmzProduct get(Long shopId, Long id);

    /**
     * 新建主数据行。
     *
     * @throws IllegalArgumentException 必填缺失，或 (shopId, sku, marketplaceId) 已存在
     */
    AmzProduct create(AmzProduct product);

    /** 更新主数据行；只允许改本店铺的行。 */
    AmzProduct update(Long shopId, Long id, AmzProduct patch);

    /**
     * 同一 ASIN 在给定店铺集合里的登记情况（跨站复制与映射的入口数据）。
     * 店铺集合必须由调用方按当前用户可访问范围传入，不能靠参数越权查别人。
     */
    List<AmzProduct> listByAsinAcrossShops(List<Long> shopIds, String asin);
}
