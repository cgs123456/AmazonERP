package com.amz.scheduler;

import com.amz.engine.ReplenishmentEngine;
import com.amz.mapper.FbaInventoryMapper;
import com.amz.mapper.ReplenishmentSuggestionMapper;
import com.amz.model.FbaInventory;
import com.amz.model.ReplenishmentSuggestion;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「库存未知」与「库存为 0」必须是两回事。
 * <p>
 * <b>实测发现的缺陷（2026-09-30 代码审查）</b>：{@code parseInventory} 用
 * {@code getInt(details, "fulfillable", 0)} —— 对端少给字段、结构变化、值不可解析，
 * 全都变成 0，并被 upsert 写进 {@code amz_fba_inventory.available_quantity}。
 * 后果不只是显示难看：补货计算把「未知」当「卖光了」，于是<b>凭空生成补货建议</b>，
 * 落到采购就是多余备货的真金白银。列可空、实体是 {@code Integer}，
 * 表达「未知」不需要改表结构。
 */
@DisplayName("库存解析：字段缺失不得伪装成 0")
class InventoryQuantityUnknownTest {

    private final InventorySyncScheduler scheduler = new InventorySyncScheduler();

    @Test
    @DisplayName("显式 0 保留为 0；字段缺失得到 null")
    void explicitZeroStaysZeroAndAbsenceIsNull() {
        FbaInventory explicit = scheduler.parseInventory(
                JsonParser.parseString("{\"sellerSku\":\"S1\",\"inventoryDetails\":{\"fulfillable\":0}}")
                        .getAsJsonObject(), 7L, "ATVPDKIKX0DER");
        assertEquals(0, explicit.getAvailableQuantity(),
                "对端明确说可售 0 时必须还是 0，不能被「未知」覆盖掉");

        FbaInventory absent = scheduler.parseInventory(
                JsonParser.parseString("{\"sellerSku\":\"S1\"}").getAsJsonObject(),
                7L, "ATVPDKIKX0DER");
        assertNull(absent.getAvailableQuantity(),
                "没给 inventoryDetails 时不能写 0 —— 那会被下游读成「卖光了」");
        assertNull(absent.getInboundWorking());
        assertNull(absent.getInboundShipped());
        assertNull(absent.getUnfulfillableQuantity());
    }

    @Test
    @DisplayName("值不可解析（对端结构变了）→ null，不是 0")
    void malformedValueIsUnknown() {
        FbaInventory inv = scheduler.parseInventory(
                JsonParser.parseString("{\"inventoryDetails\":{\"fulfillable\":\"abc\"}}").getAsJsonObject(),
                7L, "ATVPDKIKX0DER");

        assertNull(inv.getAvailableQuantity(),
                "解析失败按 0 会把接口变更伪装成缺货，现场只看到「为什么全店都在提示补货」");
    }

    @Test
    @DisplayName("unfulfillable 的对象形式与数字形式都取到数；缺失为 null")
    void unfulfillableForms() {
        FbaInventory objectForm = scheduler.parseInventory(
                JsonParser.parseString("{\"inventoryDetails\":{\"unfulfillable\":{\"totalUnfulfillable\":7}}}")
                        .getAsJsonObject(), 7L, "ATVPDKIKX0DER");
        assertEquals(7, objectForm.getUnfulfillableQuantity());

        FbaInventory plainForm = scheduler.parseInventory(
                JsonParser.parseString("{\"inventoryDetails\":{\"unfulfillable\":3}}").getAsJsonObject(),
                7L, "ATVPDKIKX0DER");
        assertEquals(3, plainForm.getUnfulfillableQuantity());

        FbaInventory missing = scheduler.parseInventory(
                JsonParser.parseString("{\"inventoryDetails\":{}}").getAsJsonObject(), 7L, "ATVPDKIKX0DER");
        assertNull(missing.getUnfulfillableQuantity());
    }

    @Test
    @DisplayName("补货计算遇到库存未知的 SKU：跳过；库存为 0 的照常计算")
    void replenishmentSkipsUnknownStock() {
        FbaInventory unknown = inventory("S-unknown", null);
        FbaInventory emptyStock = inventory("S-empty", 0);

        FbaInventoryMapper inventoryMapper = mock(FbaInventoryMapper.class);
        ReplenishmentEngine engine = mock(ReplenishmentEngine.class);
        ReplenishmentSuggestionMapper suggestionMapper = mock(ReplenishmentSuggestionMapper.class);
        when(inventoryMapper.selectList(any())).thenReturn(List.of(unknown, emptyStock));
        when(engine.getSeasonalIndex(any(), anyInt())).thenReturn(java.math.BigDecimal.ONE);
        when(engine.getActivePromotionMultiplier(any())).thenReturn(java.math.BigDecimal.ONE);
        when(engine.generateSuggestion(any(), any(), any(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new ReplenishmentSuggestion());
        when(suggestionMapper.selectOne(any())).thenReturn(null);

        ReplenishmentScheduler replenishment = new ReplenishmentScheduler();
        ReflectionTestUtils.setField(replenishment, "replenishmentEngine", engine);
        ReflectionTestUtils.setField(replenishment, "fbaInventoryMapper", inventoryMapper);
        ReflectionTestUtils.setField(replenishment, "replenishmentSuggestionMapper", suggestionMapper);

        int generated = replenishment.calcShopReplenishment(7L);

        verify(engine, never())
                .generateSuggestion(any(), eq("S-unknown"), any(), any(), anyInt(), anyInt(), any(), any());
        verify(engine)
                .generateSuggestion(eq(7L), eq("S-empty"), any(), any(), eq(0), anyInt(), any(), any());
        assertEquals(1, generated,
                "只有真实取到库存的 SKU 才该产出建议；未知的那条不能算进去");
    }

    private static FbaInventory inventory(String sku, Integer available) {
        FbaInventory inv = new FbaInventory();
        inv.setShopId(7L);
        inv.setSku(sku);
        inv.setAsin("A-" + sku);
        inv.setAvailableQuantity(available);
        return inv;
    }
}
