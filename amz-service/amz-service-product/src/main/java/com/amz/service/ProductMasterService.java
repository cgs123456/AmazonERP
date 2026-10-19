package com.amz.service;

import com.amz.dto.ProductMasterBatchPage;
import com.amz.model.AmzProduct;

import java.util.List;


/**
 * 商品主数据（amz_product）的维护契约。
 * <p>
 * 存在动因（2026-10-02 功能覆盖清点）：同一个表 {@code amz_product} 上并存的
     * 旧的 {@code model/pojo/Product} 通路（映射 name/type/image 等表里没有的列）已于
     * 2026-10-03 整体删除，商品读写只剩本服务这一条；
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

    /**
     * 全量重建搜索索引时的 keyset 分页读（跨店铺，不按用户过滤）。
     * <p>
     * <b>只供内部服务调用</b>：本方法没有店铺维度，返回的是全库商品。
     * 调用方是 {@code amz-service-search} 的索引重建，端点已由
     * {@code @InternalServiceAccess} 限制为服务身份。任何面向用户的端点
     * <b>不得</b>直接复用本方法——那会变成跨租户读取。
     *
     * @param afterId 上一页最后一条的 id；null 表示从头开始
     * @param limit   单页条数，服务端会钳到安全上限
     * @return 本页记录 + 下一页游标（null 表示末页）
     */
    ProductMasterBatchPage readBatchForIndex(Long afterId, Integer limit);
}
