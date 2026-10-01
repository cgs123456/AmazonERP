package com.amz.service.impl;

import com.amz.dto.SettlementIngestReport;
import com.amz.mapper.SettlementDetailMapper;
import com.amz.model.SettlementDetail;
import com.amz.parse.SettlementParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结算明细落库的批量路径（分块 + 失败块退回逐条）。
 * <p>
 * 关注的不是"快了多少"，而是<b>换成批量之后报表还说不说得清</b>：
 * inserted / skipped / failed / sumAmount / rowErrors 五个数必须仍然对得上，
 * 尤其"批量已经提交了几行才失败"这种半路情况，不能把已入库的行谎报成失败。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("结算落库：分块批量、退回逐条、半路失败的归属")
class SettlementBatchIngestTest {

    private static final Long SHOP_ID = 1L;
    private static final String HEADER = String.join("\t",
            "settlement-id", "settlement-start-date", "settlement-end-date", "deposit-date",
            "currency", "transaction-type", "order-id", "sku", "amount-type", "amount");

    @Mock
    private SettlementDetailMapper settlementDetailMapper;

    private SettlementServiceImpl settlementService;

    @BeforeEach
    void setUp() {
        settlementService = new SettlementServiceImpl();
        ReflectionTestUtils.setField(settlementService, "settlementDetailMapper", settlementDetailMapper);
        ReflectionTestUtils.setField(settlementService, "batchSize", 200);
        ReflectionTestUtils.setField(settlementService, "maxRowErrors", 50);
        when(settlementDetailMapper.selectExistingRowKeys(anyList())).thenReturn(List.of());
    }

    private static SettlementParser.ParseResult parse(int rows, String amount) {
        StringBuilder tsv = new StringBuilder(HEADER);
        for (int i = 0; i < rows; i++) {
            tsv.append('\n').append(String.join("\t", "900001", "2026-09-01T00:00:00Z",
                    "2026-09-07T23:59:59Z", "2026-09-08T00:00:00Z", "USD", "Order",
                    "111-" + String.format("%04d", i), "SKU-" + i, "Principal", amount));
        }
        return SettlementParser.parse(tsv.toString());
    }

    private void injectBatchWriter(Consumer<Collection<SettlementDetail>> writer) {
        ReflectionTestUtils.setField(settlementService, "batchWriter", writer);
    }

    @Test
    @DisplayName("450 行 / 块 200 → 恰好 200+200+50 三块，一次逐条都不走")
    void happyPathUsesBatchChunksOnly() {
        List<Integer> blockSizes = new ArrayList<>();
        injectBatchWriter(block -> blockSizes.add(block.size()));

        SettlementIngestReport report = new SettlementIngestReport();
        settlementService.ingestParsedRows(SHOP_ID, parse(450, "10.00").getRows(), report);

        assertEquals(List.of(200, 200, 50), blockSizes);
        assertEquals(450, report.getInserted());
        assertEquals(0, report.getSkipped());
        assertEquals(0, report.getFailed());
        assertEquals(0, new BigDecimal("4500.00").compareTo(report.getSumAmount()));
        verify(settlementDetailMapper, never()).insert(any(SettlementDetail.class));
    }

    @Test
    @DisplayName("默认批量出口绑在 MyBatis-Plus Db 上（换成别的，生产就永远只走逐条兜底）")
    void defaultBatchWriterTargetsTheMpToolkit() {
        // 反射拿合成 lambda 的目标方法不可靠，所以钉住源码里的绑定 + 字段确实存在默认值
        String source = readSource();
        assertTrue(source.contains("Db::saveBatch"),
                "batchWriter 默认实现必须绑在 MyBatis-Plus 的批量入口上");
        assertTrue(source.contains("com.baomidou.mybatisplus.extension.toolkit.Db"),
                "Db 需显式导入，别被同名类顶掉");
        org.junit.jupiter.api.Assertions.assertNotNull(
                ReflectionTestUtils.getField(new SettlementServiceImpl(), "batchWriter"));
    }

    @Test
    @DisplayName("第 2 块批量失败：只有该块退回逐条，其余两块仍走批量，金额一块不漏")
    void failingChunkFallsBackToPerRow() {
        AtomicInteger batchCalls = new AtomicInteger();
        injectBatchWriter(block -> {
            if (batchCalls.incrementAndGet() == 2) {
                throw new IllegalStateException("flush failed");
            }
        });
        when(settlementDetailMapper.insert(any(SettlementDetail.class))).thenReturn(1);

        SettlementIngestReport report = new SettlementIngestReport();
        settlementService.ingestParsedRows(SHOP_ID, parse(450, "10.00").getRows(), report);

        assertEquals(3, batchCalls.get(), "三块都尝试过批量");
        verify(settlementDetailMapper, times(200)).insert(any(SettlementDetail.class));
        assertEquals(450, report.getInserted());
        assertEquals(0, report.getFailed());
        assertEquals(0, new BigDecimal("4500.00").compareTo(report.getSumAmount()),
                "退回逐条的行金额同样要累计：" + report.getSumAmount());
    }

    @Test
    @DisplayName("批量提交一半才失败：逐条重试撞唯一键算跳过，钱仍算进本批")
    void duplicateOnRetryIsSkippedNotFailed() {
        injectBatchWriter(block -> {
            throw new IllegalStateException("Duplicate entry for key 'uk'");
        });
        when(settlementDetailMapper.insert(any(SettlementDetail.class))).thenAnswer(invocation -> {
            SettlementDetail detail = invocation.getArgument(0);
            if ("111-0003".equals(detail.getAmazonOrderId())) {
                throw new SQLIntegrityConstraintViolationException("Duplicate entry");
            }
            return 1;
        });

        SettlementIngestReport report = new SettlementIngestReport();
        settlementService.ingestParsedRows(SHOP_ID, parse(11, "1.00").getRows(), report);

        assertEquals(10, report.getInserted());
        assertEquals(1, report.getSkipped(), "重复键代表「已在库里」，不是失败");
        assertEquals(0, report.getFailed());
        assertEquals(0, new BigDecimal("11.00").compareTo(report.getSumAmount()),
                "已入库那行的钱也要算进本批合计");
        assertTrue(report.getRowErrors().isEmpty(), report.getRowErrors().toString());
    }

    @Test
    @DisplayName("逐条仍失败：计入 failed，并在 rowErrors 里留下行归属")
    void realPerRowFailuresKeepAttribution() {
        injectBatchWriter(block -> {
            throw new IllegalStateException("batch exploded");
        });
        when(settlementDetailMapper.insert(any(SettlementDetail.class))).thenAnswer(invocation -> {
            SettlementDetail detail = invocation.getArgument(0);
            if ("111-0002".equals(detail.getAmazonOrderId())) {
                throw new IllegalStateException("Data too long for column 'sku'");
            }
            return 1;
        });

        SettlementIngestReport report = new SettlementIngestReport();
        settlementService.ingestParsedRows(SHOP_ID, parse(4, "5.00").getRows(), report);

        assertEquals(3, report.getInserted());
        assertEquals(1, report.getFailed());
        assertEquals(1, report.getRowErrors().size());
        var rowError = report.getRowErrors().get(0);
        assertTrue(rowError.getReason().contains("Data too long"), rowError.getReason());
        // rawLine 存的是 row_key 指纹（唯一键本身），能定位到"哪一笔"，但不是人类可读的行号
        assertTrue(rowError.getRawLine() != null && !rowError.getRawLine().isBlank(),
                "失败必须带上可定位的行标识");
        assertEquals(0, new BigDecimal("15.00").compareTo(report.getSumAmount()));
    }

    @Test
    @DisplayName("库里已有的行不进批量：不重写、也不再算一次钱")
    void preExistingRowsAreNotRecounted() {
        SettlementParser.ParseResult parsed = parse(2, "10.00");
        String firstRowKey = parsed.getRows().get(0).getRowKey();
        when(settlementDetailMapper.selectExistingRowKeys(anyList())).thenReturn(List.of(firstRowKey));
        AtomicInteger batchCalls = new AtomicInteger();
        injectBatchWriter(block -> batchCalls.incrementAndGet());
        when(settlementDetailMapper.insert(any(SettlementDetail.class))).thenReturn(1);

        SettlementIngestReport report = new SettlementIngestReport();
        settlementService.ingestParsedRows(SHOP_ID, parsed.getRows(), report);

        assertEquals(1, report.getInserted());
        assertEquals(1, report.getSkipped());
        assertEquals(0, batchCalls.get(), "只剩 1 行待写：单行块没有批量可言，直接逐条");
        verify(settlementDetailMapper, times(1)).insert(any(SettlementDetail.class));
        assertEquals(0, new BigDecimal("10.00").compareTo(report.getSumAmount()),
                "上一批已入库的行不应再算一次钱");
    }

    private static String readSource() {
        try {
            Path path = Path.of("src/main/java/com/amz/service/impl/SettlementServiceImpl.java").toAbsolutePath();
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读不到 SettlementServiceImpl 源码，测试本身失效", e);
        }
    }
}
