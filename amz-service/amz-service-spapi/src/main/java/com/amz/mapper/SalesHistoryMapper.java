package com.amz.mapper;

import com.amz.model.SalesHistory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 销售历史 Mapper。
 * <p>
 * 本表此前全仓零写入方（{@code V11__drop_dead_table.sql} 的注释自己记着
 * 「这张表从未被写入过」），而 {@code ReplenishmentEngine} 只读它算基线日均需求，
 * 于是补货建议一直基于空历史出数。写入方见 {@code SalesHistorySyncService}。
 */
@Mapper
public interface SalesHistoryMapper extends BaseMapper<SalesHistory> {

    /**
     * 批量 upsert。唯一键 {@code uk_shop_sku_date} 已含 shop_id，冲突时覆盖本次同步的
     * 销量与金额。
     * <p>
     * 用 upsert 而不是 select-then-insert/update：同一 (shopId, sku, saleDate) 在
     * 重复回补或并发同步时必然撞键，两步走会产生竞态与「查不到却插入失败」的中间态。
     * <p>
     * 刻意覆盖而非累加：报表给出的是当日全量销量，累加会把一次重跑变成双倍。
     */
    @Insert("""
            <script>
            INSERT INTO amz_sales_history (shop_id, sku, sale_date, quantity, revenue)
            VALUES
            <foreach collection="rows" item="r" separator=",">
                (#{r.shopId}, #{r.sku}, #{r.saleDate}, #{r.quantity}, #{r.revenue})
            </foreach>
            ON DUPLICATE KEY UPDATE
                quantity = VALUES(quantity),
                revenue = VALUES(revenue)
            </script>
            """)
    int upsertBatch(@Param("rows") List<SalesHistory> rows);
}

