package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.InventoryBatchMapper;
import com.amz.model.InventoryBatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FbaShipmentFifoAtomicDeductionTest {

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    @DisplayName("FIFO 并发扣减：100 轮双线程竞争不得超卖，失败方必须得到库存不足")
    void concurrentFifoOutboundNeverOversells() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 100; round++) {
                InventoryBatchMapper mapper = mock(InventoryBatchMapper.class);
                InventoryBatch batch = batch(1L, 5);
                CyclicBarrier readBarrier = new CyclicBarrier(2);
                AtomicInteger remaining = new AtomicInteger(5);

                when(mapper.selectList(any())).thenAnswer(invocation -> {
                    readBarrier.await(5, TimeUnit.SECONDS);
                    return List.of(batch);
                });
                when(mapper.decreaseAvailableQuantityAtomic(anyLong(), anyLong(), anyString(), anyInt()))
                        .thenAnswer(invocation -> {
                            int qty = invocation.getArgument(3);
                            synchronized (remaining) {
                                int available = remaining.get();
                                if (available < qty) {
                                    return 0;
                                }
                                remaining.addAndGet(-qty);
                                return 1;
                            }
                        });

                FbaShipmentServiceImpl service = new FbaShipmentServiceImpl();
                ReflectionTestUtils.setField(service, "inventoryBatchMapper", mapper);

                Callable<Boolean> outbound = () -> {
                    try {
                        service.fifoOutbound(1L, "SKU-1", 3);
                        return true;
                    } catch (CodeErrorException expected) {
                        return false;
                    }
                };

                Future<Boolean> first = executor.submit(outbound);
                Future<Boolean> second = executor.submit(outbound);
                boolean firstSucceeded = first.get(10, TimeUnit.SECONDS);
                boolean secondSucceeded = second.get(10, TimeUnit.SECONDS);

                assertEquals(1, (firstSucceeded ? 1 : 0) + (secondSucceeded ? 1 : 0),
                        "第 " + round + " 轮必须恰好一个请求成功");
                assertEquals(2, remaining.get(),
                        "第 " + round + " 轮成功扣减 3 后应剩余 2，且不得为负数");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static InventoryBatch batch(long id, int available) {
        InventoryBatch batch = new InventoryBatch();
        batch.setId(id);
        batch.setShopId(1L);
        batch.setSku("SKU-1");
        batch.setBatchNo("BAT-" + id);
        batch.setQuantity(available);
        batch.setAvailableQuantity(available);
        batch.setUnitCost(new BigDecimal("10.00"));
        batch.setInboundDate(LocalDate.now());
        batch.setStatus("ACTIVE");
        return batch;
    }
}