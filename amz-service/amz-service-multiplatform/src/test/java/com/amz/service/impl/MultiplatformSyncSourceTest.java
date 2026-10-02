package com.amz.service.impl;

import com.amz.client.SheinClient;
import com.amz.client.TemuClient;
import com.amz.client.TikTokClient;
import com.amz.exception.AttrIsNullException;
import com.amz.mapper.PlatformInventoryMapper;
import com.amz.mapper.PlatformMessageMapper;
import com.amz.mapper.PlatformProductMapper;
import com.amz.model.PlatformInventory;
import com.amz.model.PlatformMessage;
import com.amz.model.PlatformProduct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台同步的数据来源契约。
 * <p>
 * 动因（2026-10-02 功能覆盖清点）：{@code syncProducts/syncMessages/syncInventory} 曾在方法内
 * 直接 {@code for i=1..N} 造硬编码数据写库，与 profile 无关——生产环境也会写入假商品、假站内信
 * 和随机库存；其中 {@code syncInventory} 还**先 delete 再写假数**，拉取失败时连上一轮快照一起丢。
 * 本测试钉住三件事：数据只能来自平台客户端、客户端失败时一行都不写、库存拉不到数据时不删快照。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("多平台同步：数据来源必须是客户端，失败不得写库")
class MultiplatformSyncSourceTest {

    // 必须是各平台接口本身：@InjectMocks 按字段类型注入，父接口类型的 mock 注不进 TemuClient 字段
    @Mock private TemuClient temuClient;
    @Mock private TikTokClient tiktokClient;
    @Mock private SheinClient sheinClient;
    @Mock private PlatformProductMapper platformProductMapper;
    @Mock private PlatformMessageMapper platformMessageMapper;
    @Mock private PlatformInventoryMapper platformInventoryMapper;

    @InjectMocks private MultiplatformServiceImpl service;

    @Test
    @DisplayName("商品同步只写入客户端返回的行，条数由数据源决定")
    void productsComeFromClientOnly() {
        PlatformProduct one = new PlatformProduct();
        one.setPlatformProductId("TEMU-PROD-1");
        when(temuClient.fetchProducts(7L)).thenReturn(List.of(one));
        when(platformProductMapper.selectOne(any())).thenReturn(null);

        assertEquals(1, service.syncProducts(7L, "TEMU"));
        verify(platformProductMapper).insert(any(PlatformProduct.class));
    }

    @Test
    @DisplayName("客户端未实现时向上抛出，且不写库")
    void unimplementedClientDoesNotWrite() {
        when(temuClient.fetchProducts(7L))
                .thenThrow(new UnsupportedOperationException("TEMU 平台的商品接口尚未接入"));

        assertThrows(UnsupportedOperationException.class, () -> service.syncProducts(7L, "TEMU"));
        verify(platformProductMapper, never()).insert(any(PlatformProduct.class));
        verify(platformProductMapper, never()).updateById(any(PlatformProduct.class));
    }

    @Test
    @DisplayName("站内信同步同样以客户端为唯一数据源")
    void messagesComeFromClientOnly() {
        PlatformMessage msg = new PlatformMessage();
        msg.setPlatformMessageId("TEMU-MSG-1");
        when(temuClient.fetchMessages(7L)).thenReturn(List.of(msg));
        when(platformMessageMapper.selectOne(any())).thenReturn(null);

        assertEquals(1, service.syncMessages(7L, "TEMU"));
        verify(platformMessageMapper).insert(any(PlatformMessage.class));
    }

    @Test
    @DisplayName("库存：客户端抛错时绝不删除上一轮快照")
    void inventoryFailureKeepsPreviousSnapshot() {
        when(temuClient.fetchInventory(7L))
                .thenThrow(new UnsupportedOperationException("TEMU 平台的库存接口尚未接入"));

        assertThrows(UnsupportedOperationException.class, () -> service.syncInventory(7L, "TEMU"));
        verify(platformInventoryMapper, never()).delete(any());
        verify(platformInventoryMapper, never()).insert(any(PlatformInventory.class));
    }

    @Test
    @DisplayName("库存：拉到空结果时保留旧快照而不是清空")
    void emptyInventoryKeepsPreviousSnapshot() {
        when(temuClient.fetchInventory(7L)).thenReturn(List.of());

        assertEquals(0, service.syncInventory(7L, "TEMU"));
        verify(platformInventoryMapper, never()).delete(any());
    }

    @Test
    @DisplayName("库存：拉到数据后才替换本轮快照")
    void inventoryReplacesSnapshotAfterFetch() {
        when(temuClient.fetchInventory(7L)).thenReturn(List.of(new PlatformInventory()));

        assertEquals(1, service.syncInventory(7L, "TEMU"));
        verify(platformInventoryMapper).delete(any());
        verify(platformInventoryMapper).insert(any(PlatformInventory.class));
    }

    @Test
    @DisplayName("亚马逊不在本模块拉取，报错要点名 spapi")
    void amazonIsNotHandledHere() {
        AttrIsNullException exc = assertThrows(AttrIsNullException.class,
                () -> service.syncInventory(7L, "AMAZON"));
        assertTrue(exc.getMessage().contains("spapi"),
                "报错应说明亚马逊走 spapi 侧，实际：" + exc.getMessage());
    }

    @Test
    @DisplayName("未知平台一律拒绝，不静默返回 0")
    void unknownPlatformRejected() {
        assertThrows(AttrIsNullException.class, () -> service.syncProducts(7L, "EBAY"));
        assertThrows(AttrIsNullException.class, () -> service.syncProducts(7L, null));
    }

    @Test
    @DisplayName("三个平台的分发各归各的客户端")
    void dispatchPerPlatform() {
        when(sheinClient.fetchProducts(8L)).thenReturn(List.of(new PlatformProduct()));
        when(tiktokClient.fetchProducts(9L)).thenReturn(List.of(new PlatformProduct()));
        when(platformProductMapper.selectOne(any())).thenReturn(null);

        service.syncProducts(8L, "SHEIN");
        service.syncProducts(9L, "TIKTOK");

        verify(sheinClient).fetchProducts(8L);
        verify(tiktokClient).fetchProducts(9L);
        verify(temuClient, never()).fetchProducts(any());
    }
}
