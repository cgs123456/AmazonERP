package com.amz.outbox;

import com.amz.client.SpApiGateway;
import com.amz.connector.SpApiTokenSource;
import com.amz.outbox.SpApiCallOutboxService.OutboxView;
import com.amz.outbox.SpApiCallOutboxService.ReplayCall;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Outbox 重放执行器：自动只读、人工可写、原子领取")
class SpApiOutboxReplayExecutorTest {

    @Test
    @DisplayName("自动重放 GET：领取成功后调用网关并返回成功状态")
    void automaticReplayExecutesGet() {
        SpApiCallOutboxService outbox = mock(SpApiCallOutboxService.class);
        SpApiGateway gateway = mock(SpApiGateway.class);
        SpApiOutboxReplayExecutor executor = new SpApiOutboxReplayExecutor(outbox, gateway);
        ReplayCall call = replayCall("GET", SpApiCallOutboxService.REPLAYING);

        when(outbox.claimForReplay(7L)).thenReturn(1);
        when(outbox.loadForReplay(7L)).thenReturn(call);
        when(gateway.replay(call)).thenReturn(new JsonObject());
        when(outbox.view(7L)).thenReturn(view(SpApiCallOutboxService.SUCCEEDED));

        SpApiOutboxReplayExecutor.ReplayResult result = executor.replay(7L, true);

        assertTrue(result.success());
        assertEquals("SUCCEEDED", result.status());
        verify(gateway).replay(call);
    }

    @Test
    @DisplayName("自动重放遇到 POST 必须拒绝，且不得领取或调用网关")
    void automaticReplayRefusesWriteBeforeClaim() {
        SpApiCallOutboxService outbox = mock(SpApiCallOutboxService.class);
        SpApiGateway gateway = mock(SpApiGateway.class);
        SpApiOutboxReplayExecutor executor = new SpApiOutboxReplayExecutor(outbox, gateway);

        when(outbox.loadForReplay(8L)).thenReturn(replayCall("POST", SpApiCallOutboxService.FAILED));

        SpApiOutboxReplayExecutor.ReplayResult result = executor.replay(8L, true);

        assertFalse(result.success());
        assertEquals("SKIPPED_WRITE", result.outcome());
        verify(outbox, never()).claimForReplay(8L);
        verify(gateway, never()).replay(any());
    }

    @Test
    @DisplayName("多实例竞争同一记录时，未领取成功的实例直接跳过")
    void automaticReplaySkipsWhenClaimLost() {
        SpApiCallOutboxService outbox = mock(SpApiCallOutboxService.class);
        SpApiGateway gateway = mock(SpApiGateway.class);
        SpApiOutboxReplayExecutor executor = new SpApiOutboxReplayExecutor(outbox, gateway);

        when(outbox.loadForReplay(9L)).thenReturn(replayCall("GET", SpApiCallOutboxService.FAILED));
        when(outbox.claimForReplay(9L)).thenReturn(0);

        SpApiOutboxReplayExecutor.ReplayResult result = executor.replay(9L, true);

        assertFalse(result.success());
        assertEquals("SKIPPED_CLAIMED", result.outcome());
        verify(gateway, never()).replay(any());
    }

    private static ReplayCall replayCall(String method, String status) {
        return new ReplayCall(7L, 1001L, "ATVPDKIKX0DER", "orders.getOrders",
                method, "/orders/v0/orders", "MarketplaceIds=ATVPDKIKX0DER", null,
                List.of(200), null, SpApiTokenSource.LWA, List.of(), status);
    }

    private static OutboxView view(String status) {
        return new OutboxView(7L, 1001L, "orders.getOrders", "GET", "/orders/v0/orders",
                status, 1, 4, 200, null, null, null, null,
                LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now(), List.of(200), null,
                SpApiTokenSource.LWA, null, null);
    }
}
