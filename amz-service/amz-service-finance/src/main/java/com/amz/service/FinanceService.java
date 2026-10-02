package com.amz.service;

import com.amz.dto.KingdeeSyncResult;
import com.amz.model.AccountingVoucher;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;

import java.math.BigDecimal;
import java.util.List;

/**
 * 业财一体化服务接口。
 */
public interface FinanceService {

    /**
     * 根据订单自动生成会计凭证（借应收 / 贷收入 + 多币种换算）。
     *
     * @param shopId    店铺 ID
     * @param orderNo   订单号
     * @param amount    订单金额（原币）
     * @param currency  币种
     * @return 生成的凭证
     */
    AccountingVoucher generateOrderVoucher(Long shopId, String orderNo, BigDecimal amount, String currency);

    /**
     * 生成索赔追回凭证（T08）：借 应收账款 / 贷 其他业务收入（平台赔付收入）。
     * <p>
     * 幂等：同一 {@code claimNo} 重复调用返回既有凭证 —— 索赔可能被重试提交、MQ 重投，
     * 重复入账会直接虚增利润。
     *
     * @param claimNo 索赔单号（source_no）
     */
    AccountingVoucher generateReimbursementVoucher(Long shopId, String claimNo, BigDecimal amount, String currency);

    /**
     * 同步凭证到金蝶。
     * <p>
     * 返回值必须保留 {@code SYNCED/MOCK/SKIPPED/FAILED/NOT_FOUND/FORBIDDEN/NOT_CONFIGURED}
     * 语义；禁止用 boolean 把模拟、并发跳过和真实入账混为一谈。
     */
    KingdeeSyncResult syncToKingdee(Long voucherId);

    /**
     * 查询店铺凭证列表（游标分页）。
     * <p>
     * 返回 {@link PageResult} 而不是裸 {@code List}：凭证明细由调度器持续写入，
     * 一次性取全量或硬编码 LIMIT 都会在数据量上来之后悄悄漏单，
     * 而调用方看到的仍是 HTTP 200 和一个「看起来正常」的数组。
     *
     * @param shopId     店铺 ID
     * @param sourceType 来源类型过滤；null 或空白表示不过滤
     * @param page       分页参数；null 表示首页 + 默认页大小
     * @return 本页数据 + 截断事实（{@code hasMore} / {@code nextCursor}）
     */
    PageResult<AccountingVoucher> listVouchers(Long shopId, String sourceType, PageRequest page);

    /**
     * 由已入库的结算行补齐 PLATFORM_FEE / REFUND 凭证。
     * <p>
     * {@link #calculateProfit} 早就在扣这两类成本，但此前没有任何生产者写入它们，
     * 于是利润只剩收入侧、系统性偏高。本方法是那个缺口的补法：以结算行的
     * {@code rowKey}（业务指纹）作幂等键，重复调用不会重复入账。
     * <p>
     * 只处理有符号金额非零、且币种可用于折算 CNY 的行；其余按原因分别计数返回，
     * 不静默跳过，也不按 1:1 硬编汇率。
     *
     * @param shopId 店铺 ID
     * @return 扫描/生成/跳过/截断计数
     */
    com.amz.dto.SettlementVoucherReport generateSettlementVouchers(Long shopId);

    /**
     * 由成本可确认的采购单补齐 PROCUREMENT 凭证（跨采购域读取，幂等，可重复调用）。
     * <p>
     * 采购数据归采购域，财务只读不造：读不到时对端返回失败，本方法把
     * {@code remoteDegraded=true} 带回来，绝不返回「0 张凭证」当成功——
     * 那会把「没读到」变成「这批采购没花钱」。
     *
     * @param shopId 店铺 ID
     * @return 读取/生成/幂等命中/跳过/降级计数
     */
    com.amz.dto.ProcurementVoucherReport generateProcurementVouchers(Long shopId);

    /**
     * 查询店铺某时间段内的总利润（收入 - 成本 - 费用，CNY）。
     */
    BigDecimal calculateProfit(Long shopId, String startDate, String endDate);
}
