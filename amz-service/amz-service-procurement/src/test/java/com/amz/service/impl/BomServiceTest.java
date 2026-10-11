package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.BomMapper;
import com.amz.model.Bom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BOM 展开契约（P2.4 组合装履约的前提）。
 * <p>
 * <b>存在动因</b>：组合装售出 1 个组合 SKU，实际出库 N 个子品；本地仓按子品备货，
 * 组合 SKU 没有库存行——不展开就去扣库存，扣的是一行不存在的数据。
 * 本测试钉住：递归展开（组合套组合）、叶子聚合、<b>环检测强制</b>（脏数据不能把内存吃光）。
 * <p>
 * 变异验证（写本测试时实际注入确认会红）：把环检测的 depth 判断去掉 → 第四例
 * StackOverflow 而不是点名 CodeErrorException（测试捕获不到即红）。
 */
@DisplayName("BOM 展开：递归 + 叶子聚合 + 环检测")
class BomServiceTest {

    @BeforeAll
    static void registerTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                Bom.class);
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
        UserContext.setShops(List.of(9001L));
    }

    @Test
    @DisplayName("递归展开：组合套组合，数量按层级相乘")
    void expandsRecursively() {
        BomMapper mapper = mock(BomMapper.class);
        BomService service = service(mapper);
        // 礼盒(1) = A(2) + 小礼盒(1)；小礼盒(1) = B(3)
        java.util.Map<String, List<Bom>> rows = java.util.Map.of(
                "礼盒", List.of(bom("礼盒", "A", 2), bom("礼盒", "小礼盒", 1)),
                "小礼盒", List.of(bom("小礼盒", "B", 3)));
        when(mapper.selectList(any())).thenAnswer(inv ->
                rowsByComposite(rows, invocationCompositeSku(inv)));

        List<BomService.BomLine> result = service.expand(9001L,
                List.of(new BomService.BomLine("礼盒", 1)));

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(l -> "A".equals(l.sku()) && l.quantity() == 2));
        assertTrue(result.stream().anyMatch(l -> "B".equals(l.sku()) && l.quantity() == 3));
    }

    @Test
    @DisplayName("叶子聚合：同一子品出现在多个组合时合并数量")
    void aggregatesSameLeaf() {
        BomMapper mapper = mock(BomMapper.class);
        BomService service = service(mapper);
        java.util.Map<String, List<Bom>> rows = java.util.Map.of(
                "礼盒", List.of(bom("礼盒", "A", 2)));
        when(mapper.selectList(any())).thenAnswer(inv ->
                rowsByComposite(rows, invocationCompositeSku(inv)));

        List<BomService.BomLine> result = service.expand(9001L, List.of(
                new BomService.BomLine("礼盒", 1),
                new BomService.BomLine("A", 5)));

        // 组合 SKU 自身不出现在展开结果里（本地仓没有它的库存行），只剩叶子
        assertEquals(1, result.size());
        assertTrue(result.stream().anyMatch(l -> "A".equals(l.sku()) && l.quantity() == 7),
                "叶子 A 应聚合 2+5=7");
    }

    @Test
    @DisplayName("数量校验：展开明细必须为正数")
    void rejectsNonPositiveQuantity() {
        BomMapper mapper = mock(BomMapper.class);
        BomService service = service(mapper);

        assertThrows(CodeErrorException.class, () -> service.expand(9001L,
                List.of(new BomService.BomLine("SKU-1", 0))));
        assertThrows(CodeErrorException.class, () -> service.expand(9001L,
                List.of(new BomService.BomLine("SKU-1", -1))));
    }

    @Test
    @DisplayName("环检测：A 含 B、B 含 A 必须点名报错而不是栈溢出")
    void detectsCycle() {
        BomMapper mapper = mock(BomMapper.class);
        BomService service = service(mapper);
        java.util.Map<String, List<Bom>> rows = java.util.Map.of(
                "A", List.of(bom("A", "B", 1)),
                "B", List.of(bom("B", "A", 1)));
        when(mapper.selectList(any())).thenAnswer(inv ->
                rowsByComposite(rows, invocationCompositeSku(inv)));

        CodeErrorException e = assertThrows(CodeErrorException.class, () -> service.expand(9001L,
                List.of(new BomService.BomLine("A", 1))));
        assertTrue(e.getMessage().contains("环") || e.getMessage().contains("深度"),
                "环必须被点名拦截而不是栈溢出，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("自引用：组合 SKU = 子品 SKU 直接拒绝")
    void rejectsSelfReference() {
        BomMapper mapper = mock(BomMapper.class);
        BomService service = service(mapper);
        Bom self = bom("SKU-1", "sku-1", 1);

        CodeErrorException e = assertThrows(CodeErrorException.class, () -> service.save(self));
        assertTrue(e.getMessage().contains("自引用") || e.getMessage().contains("环"), e.getMessage());
    }

    // ============ 辅助 ============

    private BomService service(BomMapper mapper) {
        BomService service = new BomService();
        ReflectionTestUtils.setField(service, "bomMapper", mapper);
        return service;
    }

    private Bom bom(String composite, String child, int qty) {
        Bom bom = new Bom();
        bom.setShopId(9001L);
        bom.setCompositeSku(composite);
        bom.setChildSku(child);
        bom.setChildQty(qty);
        return bom;
    }

    /** 从 selectList 的 wrapper 参数里提取 compositeSku：参数值里唯一的 String 即它（shopId 是 Long）。 */
    @SuppressWarnings("unchecked")
    private String invocationCompositeSku(org.mockito.invocation.InvocationOnMock invocation) {
        Object wrapper = invocation.getArgument(0);
        if (wrapper instanceof com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?> lw) {
            // paramNameValuePairs 懒填充：先物化 SQL 段（Bom 的 TableInfo 已在 @BeforeAll 注册）
            try {
                lw.getSqlSegment();
            } catch (Exception ignored) {
                // 物化失败按「查不到行」处理（返回空串 → 叶子语义）
            }
            for (Object value : lw.getParamNameValuePairs().values()) {
                if (value instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
        }
        return "";
    }

    /** 按查询的组合名从映射返回子品行（未命中 = 无 BOM，叶子）。 */
    private List<Bom> rowsByComposite(java.util.Map<String, List<Bom>> rows, String composite) {
        return rows.getOrDefault(composite, List.of());
    }
}
