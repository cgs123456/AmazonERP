package com.amz.mapper;

import com.amz.model.AccountingVoucher;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AccountingVoucherMapper extends BaseMapper<AccountingVoucher> {

    /**
     * 按业务类型 + 结算币种聚合金额（B2：利润计算不再无界 selectList 全量拉回内存）。
     * <p>
     * 返回元素：{sourceType, currency, totalCny, totalOriginal}；
     * SUM 全为 NULL 时对应项为 null，调用方按 0 处理。
     */
    @Select("<script>SELECT source_type AS sourceType, currency AS currency, "
            + "SUM(cny_amount) AS totalCny, SUM(original_amount) AS totalOriginal, "
            + "MAX(exchange_rate) AS rate "
            + "FROM amz_accounting_voucher WHERE shop_id = #{shopId} "
            + "<if test='startDate != null'>AND biz_date &gt;= #{startDate}</if>"
            + "<if test='endDate != null'>AND biz_date &lt;= #{endDate}</if>"
            + "GROUP BY source_type, currency</script>")
    List<Map<String, Object>> sumBySourceType(@Param("shopId") Long shopId,
                                             @Param("startDate") String startDate,
                                             @Param("endDate") String endDate);
}
