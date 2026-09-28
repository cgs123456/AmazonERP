package com.amz.service.impl;

import com.amz.client.KingdeeClient;
import com.amz.dto.KingdeeSyncResult;
import com.amz.exception.ConnectorException;
import com.amz.finance.CurrencyConverter;
import com.amz.mapper.AccountingVoucherMapper;
import com.amz.model.AccountingVoucher;
import com.amz.service.FinanceService;
import com.amz.result.PageRequest;
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
        AccountingVoucher existing = voucherMapper.selectOne(new LambdaQueryWrapper<AccountingVoucher>()
                .eq(AccountingVoucher::getShopId, shopId)
                .eq(AccountingVoucher::getSourceType, "ORDER")
                .eq(AccountingVoucher::getSourceNo, orderNo)
                .last("LIMIT 1"));
        if (existing != null) {
            log.info("订单凭证已存在，幂等返回：shopId={} orderNo={} voucherNo={}",
                    shopId, orderNo, existing.getVoucherNo());
            return existing;
        }

        BigDecimal cnyAmount = currencyConverter.convertToCny(amount, currency);
        BigDecimal rate = currencyConverter.getRate(currency);

        AccountingVoucher v = new AccountingVoucher();
        // 凭证编号：UUID 去横线，规避 "V"+System.currentTimeMillis() 在并发落库时
        // 撞库触发 amz_accounting_voucher.uk_voucher_no 唯一约束的问题。
        v.setVoucherNo("V" + UUID.randomUUID().toString().replace("-", ""));
        v.setShopId(shopId);
        v.setBizDate(LocalDate.now().format(FMT));
        v.setSummary("订单销售 - " + orderNo);
        v.setDebitAccount(ACCT_RECEIVABLE);
        v.setCreditAccount(ACCT_MAIN_REVENUE);
        v.setOriginalAmount(amount);
        v.setCurrency(currency);
        v.setExchangeRate(rate);
        v.setCnyAmount(cnyAmount);
        v.setSourceType("ORDER");
        v.setSourceNo(orderNo);
        v.setKingdeeSyncStatus("PENDING");
        try {
            voucherMapper.insert(v);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发窗口兜底：另一线程已插入同源凭证，查询返回既有记录。
            // 注意：走到这里 existing 恒为 null（非空已在方法开头返回），
            // 若重查仍为空必须抛异常，禁止返回 null 把 NPE 抛给上游。
            AccountingVoucher concurrent = voucherMapper.selectOne(new LambdaQueryWrapper<AccountingVoucher>()
                    .eq(AccountingVoucher::getShopId, shopId)
                    .eq(AccountingVoucher::getSourceType, "ORDER")
                    .eq(AccountingVoucher::getSourceNo, orderNo)
                    .last("LIMIT 1"));
            if (concurrent == null) {
                throw new IllegalStateException("订单凭证并发写入冲突且重查失败：orderNo=" + orderNo, e);
            }
            log.warn("订单凭证并发幂等命中：orderNo={}", orderNo);
            return concurrent;
        }
        log.info("订单凭证生成：orderNo={} 原币 {} {} → CNY {}", orderNo, amount, currency, cnyAmount);
        return v;
    }

    @Override
    public AccountingVoucher generateReimbursementVoucher(Long shopId, String claimNo,
                                                          BigDecimal amount, String currency) {
        // 幂等去重：索赔重试提交 / MQ 重投时返回既有凭证，避免重复计入赔付收入
        AccountingVoucher existing = voucherMapper.selectOne(new LambdaQueryWrapper<AccountingVoucher>()
                .eq(AccountingVoucher::getShopId, shopId)
                .eq(AccountingVoucher::getSourceType, SOURCE_REIMBURSEMENT)
                .eq(AccountingVoucher::getSourceNo, claimNo)
                .last("LIMIT 1"));
        if (existing != null) {
            log.info("索赔追回凭证已存在，幂等返回：shopId={} claimNo={} voucherNo={}",
                    shopId, claimNo, existing.getVoucherNo());
            return existing;
        }

        BigDecimal cnyAmount = currencyConverter.convertToCny(amount, currency);
        BigDecimal rate = currencyConverter.getRate(currency);

        AccountingVoucher v = new AccountingVoucher();
        v.setVoucherNo("V" + UUID.randomUUID().toString().replace("-", ""));
        v.setShopId(shopId);
        v.setBizDate(LocalDate.now().format(FMT));
        v.setSummary("平台索赔追回 - " + claimNo);
        // 借 应收账款（平台应付未付 → 已赔付即收回）/ 贷 其他业务收入
        v.setDebitAccount(ACCT_RECEIVABLE);
        v.setCreditAccount(ACCT_OTHER_REVENUE);
        v.setOriginalAmount(amount);
        v.setCurrency(currency);
        v.setExchangeRate(rate);
        v.setCnyAmount(cnyAmount);
        v.setSourceType(SOURCE_REIMBURSEMENT);
        v.setSourceNo(claimNo);
        v.setKingdeeSyncStatus("PENDING");
        try {
            voucherMapper.insert(v);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            AccountingVoucher concurrent = voucherMapper.selectOne(new LambdaQueryWrapper<AccountingVoucher>()
                    .eq(AccountingVoucher::getShopId, shopId)
                    .eq(AccountingVoucher::getSourceType, SOURCE_REIMBURSEMENT)
                    .eq(AccountingVoucher::getSourceNo, claimNo)
                    .last("LIMIT 1"));
            if (concurrent == null) {
                throw new IllegalStateException("索赔凭证并发写入冲突且重查失败：claimNo=" + claimNo, e);
            }
            log.warn("索赔凭证并发幂等命中：claimNo={}", claimNo);
            return concurrent;
        }
        log.info("索赔追回凭证生成：claimNo={} 原币 {} {} → CNY {}", claimNo, amount, currency, cnyAmount);
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
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
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
