package com.amz.mapper;

import com.amz.model.CarrierQuote;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

@Mapper
public interface CarrierQuoteMapper extends BaseMapper<CarrierQuote> {

    /**
     * 把**所有店铺**中已过失效日期、状态仍为 ACTIVE 的报价批量置为 EXPIRED，返回更新条数。
     * <p>
     * 为什么跨店铺一次更新，而不是逐店铺循环：
     * <ol>
     *   <li>报价过期是纯数据库判定（{@code expiry_date < today}），不调任何外部 API，
     *       不存在「越权访问他人店铺数据」的问题——本方法不读取业务数据，只改状态位；</li>
     *   <li>其它同步类调度器（退货同步/结算同步）必须配 {@code shop-ids} 白名单，
     *       是因为它们要拿店铺身份去调 Amazon；本任务没有这层外部调用，白名单只会
     *       变成「新增店铺后忘了加配置，报价就一直不过期」的运维陷阱；</li>
     *   <li>逐店铺循环在店铺数增长后是 N 次往返，而这一条 SQL 的 WHERE 已经带了
     *       索引友好的 {@code status} 与 {@code expiry_date} 条件。</li>
     * </ol>
     * <p>
     * 用一条 UPDATE 而非「先 select 再逐条 updateById」：后者在报价量大时既慢，
     * 又会产生「查到 100 条、更新到第 50 条时进程重启」的半完成状态。单条 UPDATE
     * 由数据库保证原子性。
     */
    @Update("UPDATE amz_carrier_quote SET status = 'EXPIRED', update_time = NOW() "
            + "WHERE status = 'ACTIVE' AND expiry_date IS NOT NULL AND expiry_date < #{today}")
    int expireOutdatedAcrossShops(@Param("today") LocalDate today);
}
