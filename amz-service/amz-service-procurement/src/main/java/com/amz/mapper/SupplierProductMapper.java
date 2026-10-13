package com.amz.mapper;

import com.amz.model.SupplierProduct;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SupplierProductMapper extends BaseMapper<SupplierProduct> {

    /**
     * 仅更新供货价维护列（供货价/起订量/交期/包装/单重/单体积/供方SKU/offer）。
     * <p>
     * SET 刻意<b>不含</b>：
     * <ul>
     *   <li>{@code is_preferred}——设首选有<b>跨行副作用</b>（要先清同 SKU 其他首选），
     *       放在逐行循环里做改写既慢又易错，CSV 批量路径直接拒绝该列、指路单条表单；</li>
     *   <li>{@code status} / 身份列（supplier_id、shop_id、sku）——它们不是"价格"，
     *       身份是定位键，状态由单据流转维护。</li>
     * </ul>
     * 按列 UPDATE 而非整行 {@code updateById}：与库存元数据更新同理，避免读-改-写窗口
     * 覆盖并发变更；未提供（保持原值）的列由调用方读行后回填再无条件 SET。
     * WHERE 带 shop_id 兜多租户；返回受影响行数，0 表示行已消失，调用方记该行失败。
     */
    @Update("UPDATE amz_supplier_product SET "
            + "supplier_sku_code = #{supplierSkuCode}, supplier_offer_id = #{supplierOfferId}, "
            + "supply_price = #{supplyPrice}, moq = #{moq}, lead_time_days = #{leadTimeDays}, "
            + "packaging_spec = #{packagingSpec}, unit_weight = #{unitWeight}, unit_volume = #{unitVolume}, "
            + "update_time = NOW() "
            + "WHERE id = #{id} AND shop_id = #{shopId}")
    int updatePriceById(SupplierProduct sp);
}
