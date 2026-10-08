package com.amz.scheduler;

import com.amz.connector.LocalApiException;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.amz.mapper.InventorySyncLogMapper;
import com.amz.model.InventorySyncLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库存同步缺凭证必须点名失败，不能返回 0 假成功（2026-10-08 修复的 B 桶缺陷）。
 * <p>
 * <b>实测发现的缺陷</b>：{@code syncShopInventory} 在 {@code credential == null ||
 * marketplaceId == null} 时只 WARN 后 {@code return 0}，而 HTTP 入口
 * {@code InventoryController.sync} 把返回值包成 {@code Result.success(0)} ——
 * 「这家店根本没配凭证」与「真同步到 0 条」在响应里完全不可区分，
 * 调用方读不出需要去录凭证。对照 {@code SpapiController.syncOrders} 已有的
 * {@code CODE_CREDENTIAL_MISSING} 口径，此处属漏改。
 * <p>
 * 修法：抛 {@link LocalApiException} 点名（CREDENTIAL_MISSING / MARKETPLACE_MISSING），
 * 且失败仍记 {@code InventorySyncLog}（供排障）；定时路径按店铺 try/catch，不中断其它店。
 */
@DisplayName("库存同步缺凭证：点名失败而非返回 0")
class InventorySyncCredentialFailClosedTest {

    private final InventorySyncScheduler scheduler = new InventorySyncScheduler();

    private ShopCredentialStore credentialStore;
    private InventorySyncLogMapper logMapper;

    private void wire(ShopCredential credential) {
        credentialStore = mock(ShopCredentialStore.class);
        logMapper = mock(InventorySyncLogMapper.class);
        when(credentialStore.get(any())).thenReturn(credential);
        ReflectionTestUtils.setField(scheduler, "shopCredentialStore", credentialStore);
        ReflectionTestUtils.setField(scheduler, "inventorySyncLogMapper", logMapper);
    }

    @Test
    @DisplayName("店铺无凭证 → 抛 CREDENTIAL_MISSING，不返回 0 成功")
    void missingCredentialFailsClosed() {
        wire(null);

        LocalApiException ex = assertThrows(LocalApiException.class,
                () -> scheduler.syncShopInventory(7L));

        assertEquals(LocalApiException.CODE_CREDENTIAL_MISSING, ex.getCode(),
                "缺凭证必须点名 CREDENTIAL_MISSING，让 HTTP 层返回 failure 而不是 success(0)");
        assertTrue(ex.getMessage().contains("shopId=7"), ex.getMessage());

        // 失败仍要留痕：排障时能查到这次为什么没同步
        ArgumentCaptor<InventorySyncLog> captor = ArgumentCaptor.forClass(InventorySyncLog.class);
        verify(logMapper).insert(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
        assertEquals(0, captor.getValue().getRecordsSynced());
    }

    @Test
    @DisplayName("凭证存在但 marketplaceId 缺失 → 抛 MARKETPLACE_MISSING（与缺凭证可区分）")
    void missingMarketplaceFailsClosedDistinctly() {
        ShopCredential credential = new ShopCredential();
        credential.setShopId(7L);
        // marketplaceId 不设 → null
        wire(credential);

        LocalApiException ex = assertThrows(LocalApiException.class,
                () -> scheduler.syncShopInventory(7L));

        assertEquals(LocalApiException.CODE_MARKETPLACE_MISSING, ex.getCode(),
                "「有凭证但缺站点」与「完全没凭证」要能区分，修复动作不同");
    }
}
