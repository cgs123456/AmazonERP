package com.amz.service.impl;

import com.amz.client.KingdeeClient;
import com.amz.client.ProcurementCostClient;
import com.amz.client.dto.RemotePurchaseOrder;
import com.amz.dto.ProcurementVoucherReport;
import com.amz.dto.KingdeeSyncResult;
import com.amz.dto.SettlementVoucherReport;
import com.amz.exception.AttrIsNullException;
import com.amz.exception.ConnectorException;
import com.amz.finance.CurrencyConverter;
import com.amz.mapper.AccountingVoucherMapper;
import com.amz.mapper.SettlementDetailMapper;
import com.amz.model.AccountingVoucher;
import com.amz.model.SettlementDetail;
import com.amz.parse.SettlementClassifier;
import com.amz.service.FinanceService;
import com.amz.util.MapArgUtils;
import com.amz.result.PageMeta;
import com.amz.result.PageRequest;
import com.amz.result.Result;
import com.amz.result.PageResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 业财一体化服务实现。
 * <p>
 * 自动凭证生成 + 多币种核算 + 金蝶同步。
 */
@Slf4j
@Service
public class FinanceServiceImpl implements FinanceService {

    /** 会计科目代码（参考金蝶标准科目） */
    private static final String ACCT_RECEIVABLE = "1122";      // 应收账款
    private static final String ACCT_MAIN_REVENUE = "6001";    // 主营业务收入
    private static final String ACCT_INVENTORY = "1405";       // 库存商品
    private static final String ACCT_PAYABLE = "2202";         // 应付账款
    private static final String ACCT_SALES_FEE = "6601";       // 销售费用
    private static final String ACCT_BANK = "1002";            // 银行存款
    private static final String ACCT_OTHER_REVENUE = "6051";   // 其他业务收入（平台赔付追回）

    /** 凭证来源类型：平台索赔追回（T08）。 */
    private static final String SOURCE_REIMBURSEMENT = "REIMBURSEMENT";
    private static final String SOURCE_ORDER = "ORDER";
    private static final String SOURCE_PLATFORM_FEE = "PLATFORM_FEE";
    private static final String SOURCE_REFUND = "REFUND";
    private static final String SOURCE_PROCUREMENT = "PROCUREMENT";

    /** 采购凭证源的页大小与最大翻页数：翻页读到尽为止，读到上限也要如实 capped */
    private static final int PROCUREMENT_VOUCHER_PAGE_SIZE = 200;
    private static final int PROCUREMENT_VOUCHER_MAX_PAGES = 50;

    /** 采购单没有币种列，1688 报价与支付都是人民币，凭证按 CNY 记账 */
    private static final String PROCUREMENT_CURRENCY = "CNY";

    /**
     * 结算行扫描上限：一次调用不把整表拉进内存；命中上限要在报告里显式 capped，
     * 调用方据此再跑一次，而不是把「扫到上限」误读成「这个店只有这些结算行」。
     */
    private static final int SETTLEMENT_SCAN_CAP = 5000;
    private static final int SETTLEMENT_SCAN_PAGE = 500;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 结算币种 → 销售国家码（VatService#calculateVat 第二个参数是国家码而非币种）。
     * <p>
     * 仅收录无歧义的映射；欧元等多国共用币种无法反推国家，返回 null 由调用方
     * 回退原逻辑（VatService 默认税率），避免静默用错税率。
     */
    private static final Map<String, String> CURRENCY_COUNTRY = Map.of(
            "USD", "US",
            "GBP", "UK",
            "CNY", "CN",
            "JPY", "JP");

    @Autowired
    private AccountingVoucherMapper voucherMapper;

    @Autowired
    private CurrencyConverter currencyConverter;

    @Autowired
    private SettlementDetailMapper settlementDetailMapper;

    @Autowired
    private ProcurementCostClient procurementCostClient;

    @Autowired
    private KingdeeClient kingdeeClient;

    /**
     * SYNCING 认领租约。超过该时长仍处于 SYNCING，视为进程崩溃或调用方失联，
     * 允许后续请求重新认领，避免永久卡死。
     */
    @Value("${amz.finance.kingdee.sync-lease-seconds:300}")
    private long syncLeaseSeconds = 300;

    @Autowired
    private com.amz.service.VatService vatService;

    @Override
    public AccountingVoucher generateOrderVoucher(Long shopId, String orderNo, BigDecimal amount, String currency) {
        // 幂等去重：同一订单重复触发（MQ 重投 / Feign 重试）时返回既有凭证，
        // 避免重复凭证导致 calculateProfit 重复计入收入
        AccountingVoucher existing = findVoucher(shopId, SOURCE_ORDER, orderNo);
        if (existing != null) {
            log.info("订单凭证已存在，幂等返回：shopId={} orderNo={} voucherNo={}",
                    shopId, orderNo, existing.getVoucherNo());
            return existing;
        }
        AccountingVoucher v = buildVoucher(shopId, "订单销售 - " + orderNo,
                ACCT_RECEIVABLE, ACCT_MAIN_REVENUE, amount, currency, SOURCE_ORDER, orderNo);
        try {
            voucherMapper.insert(v);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发窗口兜底：另一线程已插入同源凭证，查询返回既有记录。
            // 注意：走到这里 existing 恒为 null（非空已在方法开头返回），
            // 若重查仍为空必须抛异常，禁止返回 null 把 NPE 抛给上游。
            AccountingVoucher concurrent = requireExisting(shopId, SOURCE_ORDER, orderNo, e);
            log.warn("订单凭证并发幂等命中：orderNo={}", orderNo);
            return concurrent;
        }
        log.info("订单凭证生成：orderNo={} 原币 {} {} → CNY {}", orderNo, amount, currency, v.getCnyAmount());
        return v;
    }

    @Override
    public AccountingVoucher generateReimbursementVoucher(Long shopId, String claimNo,
                                                          BigDecimal amount, String currency) {
        // 幂等去重：索赔重试提交 / MQ 重投时返回既有凭证，避免重复计入赔付收入
        AccountingVoucher existing = findVoucher(shopId, SOURCE_REIMBURSEMENT, claimNo);
        if (existing != null) {
            log.info("索赔追回凭证已存在，幂等返回：shopId={} claimNo={} voucherNo={}",
                    shopId, claimNo, existing.getVoucherNo());
            return existing;
        }
        // 借 应收账款（平台应付未付 → 已赔付即收回）/ 贷 其他业务收入
        AccountingVoucher v = buildVoucher(shopId, "平台索赔追回 - " + claimNo,
                ACCT_RECEIVABLE, ACCT_OTHER_REVENUE, amount, currency, SOURCE_REIMBURSEMENT, claimNo);
        try {
            voucherMapper.insert(v);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            AccountingVoucher concurrent = requireExisting(shopId, SOURCE_REIMBURSEMENT, claimNo, e);
            log.warn("索赔凭证并发幂等命中：claimNo={}", claimNo);
            return concurrent;
        }
        log.info("索赔追回凭证生成：claimNo={} 原币 {} {} → CNY {}", claimNo, amount, currency, v.getCnyAmount());
        return v;
    }

    /**
     * 由结算行补齐 PLATFORM_FEE / REFUND 凭证。
     * <p>
     * calculateProfit 一直会扣这两类，但此前无人写入，利润因此只剩收入侧。
     * 刻意不加 @Transactional：本方法按 (shopId, sourceType, sourceNo) 幂等，
     * 中途失败时已写入的凭证是有效结果，重跑只补差额；把 5000 行包进一个长事务
     * 反而会让失败时的回滚把有效凭证一起丢掉。
     */
    @Override
    public SettlementVoucherReport generateSettlementVouchers(Long shopId) {
        if (shopId == null) {
            throw new AttrIsNullException("shopId 不能为空");
        }
        SettlementVoucherReport report = new SettlementVoucherReport();
        report.setShopId(shopId);
        long lastId = 0L;
        while (report.getScanned() < SETTLEMENT_SCAN_CAP) {
            int limit = Math.min(SETTLEMENT_SCAN_PAGE, SETTLEMENT_SCAN_CAP - report.getScanned());
            List<SettlementDetail> page = settlementDetailMapper.selectList(
                    new LambdaQueryWrapper<SettlementDetail>()
                            .eq(SettlementDetail::getShopId, shopId)
                            .gt(SettlementDetail::getId, lastId)
                            .orderByAsc(SettlementDetail::getId)
                            .last("LIMIT " + limit));
            if (page.isEmpty()) {
                break;
            }
            for (SettlementDetail row : page) {
                report.setScanned(report.getScanned() + 1);
                lastId = row.getId();
                applySettlementRow(shopId, row, report);
            }
            if (page.size() < limit) {
                break;
            }
        }
        report.setCapped(report.getScanned() >= SETTLEMENT_SCAN_CAP);
        if (report.isCapped()) {
            log.warn("结算凭证生成命中扫描上限：shopId={} cap={}，仍有未扫结算行，需再跑一次",
                    shopId, SETTLEMENT_SCAN_CAP);
        }
        log.info("结算凭证生成 shopId={} 扫描={} 费用凭证={} 退款凭证={} 幂等命中={} 跳过(类型/无币种/零金额)={}/{}/{}",
                shopId, report.getScanned(), report.getFeeVouchers(), report.getRefundVouchers(),
                report.getExisting(), report.getSkippedByKind(), report.getSkippedNoCurrency(),
                report.getSkippedZeroAmount());
        return report;
    }

    private void applySettlementRow(Long shopId, SettlementDetail row, SettlementVoucherReport report) {
        SettlementClassifier.Kind kind = SettlementClassifier.classify(row);
        if (!SettlementClassifier.voucherizable(kind)) {
            // PRINCIPAL 由订单凭证覆盖，ADJUSTMENT 属索赔链路：出凭证会重复计入利润
            report.setSkippedByKind(report.getSkippedByKind() + 1);
            return;
        }
        String currency = row.getCurrency();
        if (currency == null || currency.isBlank()) {
            // 无币种无法折 CNY；按 1:1 硬编会把美元扣费当人民币扣费，宁可跳过并计数
            report.setSkippedNoCurrency(report.getSkippedNoCurrency() + 1);
            return;
        }
        // 结算行扣项为负：取反后凭证是「正数的成本/退款」，与 calculateProfit 的减项方向一致
        BigDecimal original = (row.getAmount() == null ? BigDecimal.ZERO : row.getAmount()).negate();
        if (original.signum() == 0) {
            report.setSkippedZeroAmount(report.getSkippedZeroAmount() + 1);
            return;
        }
        boolean isFee = kind == SettlementClassifier.Kind.FEE;
        String sourceType = isFee ? SOURCE_PLATFORM_FEE : SOURCE_REFUND;
        // row_key 是结算行的业务指纹（唯一索引），重导同一行不会生成第二张凭证；
        // 历史行缺指纹时退到行 PK，并同样保证幂等
        String sourceNo = row.getRowKey() == null || row.getRowKey().isBlank()
                ? "SD-" + row.getId() : row.getRowKey();
        if (findVoucher(shopId, sourceType, sourceNo) != null) {
            report.setExisting(report.getExisting() + 1);
            return;
        }
        AccountingVoucher v = buildVoucher(shopId,
                (isFee ? "平台结算扣费 - " : "平台退款 - ") + (row.getAmazonOrderId() == null ? row.getSku() : row.getAmazonOrderId()),
                isFee ? ACCT_SALES_FEE : ACCT_MAIN_REVENUE,
                isFee ? ACCT_BANK : ACCT_RECEIVABLE,
                original, currency, sourceType, sourceNo);
        try {
            voucherMapper.insert(v);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 与另一路并发（或上一轮未提交的同键行）撞上：按幂等命中处理
            requireExisting(shopId, sourceType, sourceNo, e);
            report.setExisting(report.getExisting() + 1);
            return;
        }
        if (isFee) {
            report.setFeeVouchers(report.getFeeVouchers() + 1);
        } else {
            report.setRefundVouchers(report.getRefundVouchers() + 1);
        }
        report.setOriginalAmountSum(report.getOriginalAmountSum().add(original));
    }

    /**
     * 由采购域读成本可确认的采购单，补齐 PROCUREMENT 凭证。
     * <p>
     * 同样不加 @Transactional：按 (shopId, PROCUREMENT, orderNo) 幂等，
     * 中途降级时已生成的凭证是有效结果，下次接着补。
     */
    @Override
    public ProcurementVoucherReport generateProcurementVouchers(Long shopId) {
        if (shopId == null) {
            throw new AttrIsNullException("shopId 不能为空");
        }
        ProcurementVoucherReport report = new ProcurementVoucherReport();
        report.setShopId(shopId);
        String cursor = null;
        for (int page = 0; page < PROCUREMENT_VOUCHER_MAX_PAGES; page++) {
            Result<List<RemotePurchaseOrder>> res;
            try {
                res = procurementCostClient.listVoucherSources(shopId, PROCUREMENT_VOUCHER_PAGE_SIZE, cursor);
            } catch (Exception e) {
                report.setRemoteDegraded(true);
                report.setRemoteMessage("采购域调用异常：" + e.getMessage());
                log.error("拉取采购凭证源异常 shopId={}", shopId, e);
                return report;
            }
            if (res == null || res.getCode() != 200) {
                report.setRemoteDegraded(true);
                report.setRemoteMessage("采购域未返回成功：" + (res == null ? "响应为空" : res.getMessage()));
                log.warn("采购凭证源降级 shopId={} message={}", shopId, report.getRemoteMessage());
                return report;
            }
            report.setPagesRead(report.getPagesRead() + 1);
            List<RemotePurchaseOrder> rows = res.getData() == null ? List.of() : res.getData();
            for (RemotePurchaseOrder order : rows) {
                report.setScanned(report.getScanned() + 1);
                applyPurchaseOrder(shopId, order, report);
            }
            PageMeta meta = res.getPage();
            if (meta == null || !meta.isHasMore() || meta.getNextCursor() == null) {
                cursor = null;
                break;
            }
            cursor = meta.getNextCursor();
        }
        report.setCapped(cursor != null);
        if (report.isCapped()) {
            log.warn("采购凭证生成达到最大页数 shopId={} pages={}，仍有采购单未读，需再跑一次",
                    shopId, PROCUREMENT_VOUCHER_MAX_PAGES);
        }
        log.info("采购凭证生成 shopId={} 读取={} 生成={} 幂等命中={} 跳过(无单号/零金额)={}/{} 降级={}",
                shopId, report.getScanned(), report.getGenerated(), report.getExisting(),
                report.getSkippedNoOrderNo(), report.getSkippedZeroAmount(), report.isRemoteDegraded());
        return report;
    }

    private void applyPurchaseOrder(Long shopId, RemotePurchaseOrder order, ProcurementVoucherReport report) {
        if (order.getOrderNo() == null || order.getOrderNo().isBlank()) {
            // 没有单号就没有幂等键，重复跑会重复入账，只能跳过并计数
            report.setSkippedNoOrderNo(report.getSkippedNoOrderNo() + 1);
            return;
        }
        BigDecimal amount = order.getTotalAmount();
        if (amount == null && order.getUnitPrice() != null && order.getQuantity() != null) {
            amount = order.getUnitPrice().multiply(BigDecimal.valueOf(order.getQuantity()));
        }
        if (amount == null || amount.signum() == 0) {
            report.setSkippedZeroAmount(report.getSkippedZeroAmount() + 1);
            return;
        }
        if (findVoucher(shopId, SOURCE_PROCUREMENT, order.getOrderNo()) != null) {
            report.setExisting(report.getExisting() + 1);
            return;
        }
        // 借 库存商品 / 贷 应付账款：与 calculateProfit 对 PROCUREMENT 做减法的方向一致
        AccountingVoucher v = buildVoucher(shopId, "采购成本 - " + order.getOrderNo(),
                ACCT_INVENTORY, ACCT_PAYABLE, amount, PROCUREMENT_CURRENCY, SOURCE_PROCUREMENT, order.getOrderNo());
        try {
            voucherMapper.insert(v);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            requireExisting(shopId, SOURCE_PROCUREMENT, order.getOrderNo(), e);
            report.setExisting(report.getExisting() + 1);
            return;
        }
        report.setGenerated(report.getGenerated() + 1);
        report.setOriginalAmountSum(report.getOriginalAmountSum().add(amount));
    }

    /** 同源凭证查询：(shopId, sourceType, sourceNo) 三元组即幂等键。 */
    private AccountingVoucher findVoucher(Long shopId, String sourceType, String sourceNo) {
        return voucherMapper.selectOne(new LambdaQueryWrapper<AccountingVoucher>()
                .eq(AccountingVoucher::getShopId, shopId)
                .eq(AccountingVoucher::getSourceType, sourceType)
                .eq(AccountingVoucher::getSourceNo, sourceNo)
                .last("LIMIT 1"));
    }

    /** 唯一键冲突后重查仍为空，说明撞到了别的约束：抛错，不返回 null 把 NPE 传给上游。 */
    private AccountingVoucher requireExisting(Long shopId, String sourceType, String sourceNo, Exception cause) {
        AccountingVoucher concurrent = findVoucher(shopId, sourceType, sourceNo);
        if (concurrent == null) {
            throw new IllegalStateException(
                    "凭证并发写入冲突且重查失败：" + sourceType + " sourceNo=" + sourceNo, cause);
        }
        return concurrent;
    }

    private AccountingVoucher buildVoucher(Long shopId, String summary, String debitAccount, String creditAccount,
                                           BigDecimal originalAmount, String currency,
                                           String sourceType, String sourceNo) {
        AccountingVoucher v = new AccountingVoucher();
        // 凭证编号：UUID 去横线，规避 "V"+System.currentTimeMillis() 在并发落库时
        // 撞库触发 amz_accounting_voucher.uk_voucher_no 唯一约束的问题。
        v.setVoucherNo("V" + UUID.randomUUID().toString().replace("-", ""));
        v.setShopId(shopId);
        v.setBizDate(LocalDate.now().format(FMT));
        v.setSummary(summary);
        v.setDebitAccount(debitAccount);
        v.setCreditAccount(creditAccount);
        v.setOriginalAmount(originalAmount);
        v.setCurrency(currency);
        v.setExchangeRate(currencyConverter.getRate(currency));
        v.setCnyAmount(currencyConverter.convertToCny(originalAmount, currency));
        v.setSourceType(sourceType);
        v.setSourceNo(sourceNo);
        v.setKingdeeSyncStatus("PENDING");
        return v;
    }

    @Override
    public KingdeeSyncResult syncToKingdee(Long voucherId) {
        AccountingVoucher v = voucherMapper.selectById(voucherId);
        if (v == null) {
            return KingdeeSyncResult.notFound(voucherId);
        }
        // 多租户越权防护：sync 端点仅携带 voucherId，无 shopId 参数可被 ShopScoped 切面拦截，
        // 此处在服务层显式校验当前用户是否被授权操作该凭证所属店铺
        if (!com.amz.context.UserContext.isShopAllowed(v.getShopId())) {
            log.warn("syncToKingdee 越权拦截：voucherId={} shopId={} userId={}",
                    voucherId, v.getShopId(), com.amz.context.UserContext.getUserId());
            return KingdeeSyncResult.forbidden(voucherId);
        }
        // 原子认领：PENDING/FAILED，或租约已过期的 SYNCING 才允许发起同步，
        // 既防并发双写金蝶，也避免进程崩溃后永久卡在 SYNCING。
        // 使用字符串列名 UpdateWrapper（Lambda 版在无 MyBatis-Plus 元数据缓存的
        // 纯单测环境下无法解析实体列）。
        LocalDateTime staleBefore = LocalDateTime.now().minusSeconds(Math.max(1L, syncLeaseSeconds));
        boolean mockClient = kingdeeClient.isMock();
        boolean claimed = voucherMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<AccountingVoucher>()
                        .eq("id", voucherId)
                        .and(w -> {
                            w.in("kingdee_sync_status", "PENDING", "FAILED");
                            // 演示期产生的 MOCK 不是真实入账，不能永久锁死。
                            // 切到真实客户端后允许原子认领并补同步；仍为 Mock 时保持幂等。
                            if (!mockClient) {
                                w.or().eq("kingdee_sync_status", "MOCK");
                            }
                            w.or(n -> n.eq("kingdee_sync_status", "SYNCING")
                                    .and(s -> s.isNull("update_time")
                                            .or()
                                            .lt("update_time", staleBefore)));
                        })
                        .set("kingdee_sync_status", "SYNCING")) > 0;
        if (!claimed) {
            if ("SYNCED".equals(v.getKingdeeSyncStatus())) {
                log.info("凭证已同步金蝶，幂等返回：voucherId={}", voucherId);
                return KingdeeSyncResult.synced(voucherId, null);
            }
            if ("MOCK".equals(v.getKingdeeSyncStatus())) {
                log.info("凭证处于模拟同步状态，未真实入账：voucherId={}", voucherId);
                return KingdeeSyncResult.mock(voucherId, null);
            }
            log.info("凭证已被其他请求认领或状态已变化，跳过：voucherId={} status={}",
                    voucherId, v.getKingdeeSyncStatus());
            return KingdeeSyncResult.skipped(voucherId,
                    "同步正在处理中或状态已变化，本次未重复调用金蝶");
        }
        try {
            String kingdeeNo = kingdeeClient.syncVoucher(v);
            if (mockClient) {
                if (kingdeeNo == null || kingdeeNo.isBlank()) {
                    throw new IllegalStateException("金蝶模拟客户端未返回模拟凭证号");
                }
                v.setKingdeeSyncStatus("MOCK");
                voucherMapper.updateById(v);
                log.info("凭证同步金蝶模拟完成：voucherId={} mockNo={} status=MOCK（未真实入账）",
                        voucherId, kingdeeNo);
                return KingdeeSyncResult.mock(voucherId, kingdeeNo);
            }
            // 真实实现只有拿到金蝶返回的有效凭证号才能确认成功。空值或占位号
            // 一律进入 FAILED，禁止把“尚未调用”伪装成成功。
            if (kingdeeNo == null || kingdeeNo.isBlank()
                    || kingdeeNo.startsWith("KINGDEE_MOCK_")) {
                throw new IllegalStateException("金蝶未返回有效凭证号：" + kingdeeNo);
            }
            v.setKingdeeSyncStatus("SYNCED");
            voucherMapper.updateById(v);
            log.info("凭证同步金蝶完成：voucherId={} kingdeeNo={} status={}",
                    voucherId, kingdeeNo, v.getKingdeeSyncStatus());
            return KingdeeSyncResult.synced(voucherId, kingdeeNo);
        } catch (ConnectorException e) {
            markFailed(v, voucherId, e);
            if (e.getReason() == ConnectorException.Reason.NOT_CONFIGURED) {
                return KingdeeSyncResult.notConfigured(voucherId, e.getMessage());
            }
            return KingdeeSyncResult.failed(voucherId, e.getMessage());
        } catch (Exception e) {
            markFailed(v, voucherId, e);
            return KingdeeSyncResult.failed(voucherId, e.getMessage());
        }
    }

    private void markFailed(AccountingVoucher voucher, Long voucherId, Exception error) {
        voucher.setKingdeeSyncStatus("FAILED");
        voucherMapper.updateById(voucher);
        log.error("凭证同步金蝶失败：voucherId={}", voucherId, error);
    }



    /**
     * 聚合值为 null 安全转 BigDecimal（SUM 全 NULL 时 JDBC 返回 null；数字类型直接转换）。
     */
    private static BigDecimal toBigDecimal(Object value) {
        return MapArgUtils.toBigDecimal(value, BigDecimal.ZERO);
    }

    /**
     * 凭证列表：keyset 分页 + 显式截断。
     * <p>
     * 三处刻意取舍：
     * <ol>
     *   <li>排序键固定为 {@code id DESC}，游标即上一页最后一行的 id。
     *       凭证由调度器持续写入，若用 OFFSET，新凭证会把后续页整体右移，
     *       翻页必然重复或漏行；keyset 与写入无关，顺序稳定。</li>
     *   <li>按 {@code size + 1} 行探测而不是额外 COUNT(*)：大表上 COUNT 常比取一页更贵，
     *       而本接口只需要知道「还有没有下一页」。</li>
     *   <li>截断时打 WARN。对账漏单在响应里只是个布尔位，很容易被忽略；
     *       日志是运维真正会看的地方。</li>
     * </ol>
     */
    @Override
    public PageResult<AccountingVoucher> listVouchers(Long shopId, String sourceType, PageRequest page) {
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        LambdaQueryWrapper<AccountingVoucher> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AccountingVoucher::getShopId, shopId);
        if (sourceType != null && !sourceType.isBlank()) {
            wrapper.eq(AccountingVoucher::getSourceType, sourceType);
        }
        if (req.hasCursor()) {
            wrapper.lt(AccountingVoucher::getId, req.cursorId());
        }
        wrapper.orderByDesc(AccountingVoucher::getId)
                .last("LIMIT " + req.probeSize());
        List<AccountingVoucher> rows = voucherMapper.selectList(wrapper);
        if (rows.size() > req.size()) {
            log.warn("凭证列表被截断：shopId={} sourceType={} size={} 实际命中>{}，调用方需携带 nextCursor 继续翻页",
                    shopId, sourceType, req.size(), req.size());
        }
        return PageResult.of(rows, req.size(), v -> PageRequest.encodeCursor(v.getId()));
    }

    @Override
    public BigDecimal calculateProfit(Long shopId, String startDate, String endDate) {
        // 业财一体化利润汇总：按 sourceType 区分借贷方向加减
        //   ORDER        借应收 / 贷主营业务收入        → 收入（贷方）         + cnyAmount
        //   PROCUREMENT  借库存商品 / 贷应付账款        → 采购成本（借方）     - cnyAmount
        //   PLATFORM_FEE 借销售费用 / 贷银行存款        → 平台费用（借方）     - cnyAmount
        //   REFUND       借销售退回 / 贷应收账款        → 退款（借方冲减收入） - cnyAmount
        //   REIMBURSEMENT 借应收账款 / 贷其他业务收入     → 平台赔付追回         + cnyAmount
        // 其他类型忽略（向前兼容）
        // B2：按 (sourceType, currency) 在 SQL 层聚合，内存占用从 O(凭证行数) 降到 O(分组数）。
        // 口径与逐行版严格一致：ORDER 加 cny 并按原币计 VAT；PROCUREMENT/PLATFORM_FEE/REFUND 减；其他忽略。
        List<Map<String, Object>> groups = voucherMapper.sumBySourceType(shopId, startDate, endDate);

        BigDecimal profit = BigDecimal.ZERO;
        BigDecimal totalVat = BigDecimal.ZERO;
        for (Map<String, Object> g : groups) {
            if (g == null) {
                continue;
            }
            BigDecimal amount = toBigDecimal(g.get("totalCny"));
            String sourceType = g.get("sourceType") == null ? null : g.get("sourceType").toString();
            if ("ORDER".equals(sourceType)) {
                profit = profit.add(amount);
                // VAT deduction: calculate VAT on original sales amount.
                // 注意 calculateVat 第二个参数是国家码：币种可明确映射的先转换，
                // 映射不到（如 EUR）时透传原值、由 VatService 默认税率处理（与修复前一致）。
                BigDecimal originalAmount = toBigDecimal(g.get("totalOriginal"));
                Object currency = g.get("currency");
                String country = currency == null ? null
                        : CURRENCY_COUNTRY.getOrDefault(currency.toString().toUpperCase(),
                                currency.toString());
                BigDecimal vatAmount = vatService.calculateVat(originalAmount, country);
                if (vatAmount != null) {
                    // 币种单位对齐：VAT 按原币算出，必须折成 CNY 才能从 CNY 利润中扣除；
                    // 无汇率信息时按 1 处理（保持旧口径，不静默放大误差）
                    BigDecimal rate = toBigDecimal(g.get("rate"));
                    if (rate.compareTo(BigDecimal.ZERO) <= 0) {
                        rate = BigDecimal.ONE;
                    }
                    totalVat = totalVat.add(vatAmount.multiply(rate));
                }
            } else if (SOURCE_REIMBURSEMENT.equals(sourceType)) {
                // 平台索赔追回：借 应收账款 / 贷 其他业务收入 → 收入增加（追回的钱是实打实的利润）
                profit = profit.add(amount);
            } else if ("PROCUREMENT".equals(sourceType)
                    || "PLATFORM_FEE".equals(sourceType)
                    || "REFUND".equals(sourceType)) {
                profit = profit.subtract(amount);
            }
        }
        // Deduct total VAT from net profit
        profit = profit.subtract(totalVat);
        log.debug("利润计算 shopId={} 毛利润={} VAT扣除={} 净利润={}", shopId, profit.add(totalVat), totalVat, profit);
        return profit.setScale(2, RoundingMode.HALF_UP);
    }
}
