package com.amz.mapper;

import com.amz.model.ListingHealth;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface ListingHealthMapper extends BaseMapper<ListingHealth> {

    /**
     * 整店健康度汇总（total / 各严重度计数 / 分数合计）。
     * <p>
     * 存在的理由：原先为算这几个数要把整店 {@code amz_listing_health} 读进内存，
     * 行数随每天每 ASIN 一条快照线性增长；而汇总口径又不能被列表接口的单读上限截断，
     * 于是"为了不出错只能全量扫"。这里把计数与求和交给数据库，Java 只保留口径换算。
     * <p>
     * <b>severity 必须按大小写敏感比较</b>（实测 MySQL 8.4.11，本表 DDL 是
     * {@code COLLATE=utf8mb4_unicode_ci}）：同一份 fixture 里
     * {@code severity = 'OK'} 数到 3 行（'OK'/'ok'/'Ok' 全算），
     * 而 {@code severity COLLATE utf8mb4_bin = 'OK'} 数到 1 行 —— 后者才与原来
     * Java 侧 {@code "OK".equals(severity)} 一致。少了这个 COLLATE，
     * {@code healthRate} 会在无人察觉的情况下变大。
     * <p>
     * 分数用 SUM/COUNT 回传而不是 {@code AVG}：调用方要保持
     * {@code Math.round(avg * 10) / 10.0} 的换算与原来 {@code IntStream.average()}
     * 完全一致（AVG 返回 DECIMAL，标度不同会在四舍五入边界上给出不同结果）。
     * {@code health_score} 在 DDL 里可空，{@code COUNT(health_score)} 只数非空行，
     * 因此全 NULL 时 {@code scoreCount=0}，调用方按"无数据"处理。
     */
    @Select("SELECT COUNT(1) AS total, "
            + "SUM(CASE WHEN severity COLLATE utf8mb4_bin = 'OK' THEN 1 ELSE 0 END) AS okCount, "
            + "SUM(CASE WHEN severity COLLATE utf8mb4_bin = 'WARNING' THEN 1 ELSE 0 END) AS warningCount, "
            + "SUM(CASE WHEN severity COLLATE utf8mb4_bin = 'CRITICAL' THEN 1 ELSE 0 END) AS criticalCount, "
            + "SUM(health_score) AS scoreSum, "
            + "COUNT(health_score) AS scoreCount "
            + "FROM amz_listing_health WHERE shop_id = #{shopId}")
    Map<String, Object> aggregateHealthSummary(@Param("shopId") Long shopId);

    /**
     * 分数最低的 N 条 Listing（展示用）。
     * <p>
     * 只取展示需要的四列并按分数升序截断，替代原来"整店读进来再 {@code limit(5)}"。
     * 加了 {@code id ASC} 作为同分次序的判别式：同分行原先由 DB 任意排序，
     * 同一份数据两次请求可能给出不同清单。
     * <p>
     * 列必须带驼峰别名：返回类型是 {@code List<Map>}，MyBatis 直接用<b>列标签</b>当键，
     * {@code map-underscore-to-camel-case} 只对 POJO 映射生效。少了别名调用方按
     * {@code healthScore} 取到的是 null —— 这个坑是真库 IT 抓的，mock 测不出来。
     */
    @Select("SELECT asin AS asin, health_score AS healthScore, severity AS severity, "
            + "suppressed_reason AS suppressedReason "
            + "FROM amz_listing_health WHERE shop_id = #{shopId} "
            + "ORDER BY health_score ASC, id ASC LIMIT #{limit}")
    List<Map<String, Object>> selectWorstListings(@Param("shopId") Long shopId,
                                                  @Param("limit") int limit);
}
