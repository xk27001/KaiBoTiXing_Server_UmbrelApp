package com.kaibotixing.crawler;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyPoolServiceTest {

    @Test
    void testRefreshMinutesClamp() {
        ProxyPoolService service = new ProxyPoolService();

        service.setRefreshMinutes(40);
        assertEquals(40, service.getRefreshMinutes());

        service.setRefreshMinutes(0);
        assertEquals(1, service.getRefreshMinutes());

        service.setRefreshMinutes(10_000);
        assertEquals(1_440, service.getRefreshMinutes());
    }
    @Test
    void testParallelismClamp() {
        ProxyPoolService service = new ProxyPoolService();

        service.setParallelism(200);
        assertEquals(200, service.getParallelism());

        service.setParallelism(0);
        assertEquals(1, service.getParallelism());

        service.setParallelism(5_000);
        assertEquals(1_000, service.getParallelism());
    }
    @Test
    void testValidateSampleCountClamp() {
        ProxyPoolService service = new ProxyPoolService();

        service.setValidateSampleCount(123);
        assertEquals(123, service.getValidateSampleCount());

        service.setValidateSampleCount(0);
        assertEquals(1, service.getValidateSampleCount());

        service.setValidateSampleCount(20_000);
        assertEquals(10_000, service.getValidateSampleCount());
    }

    @Test
    void testAutomaticRefreshContinuesAfterUnexpectedFailure() throws InterruptedException {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch completedAttempts = new CountDownLatch(3);
        ProxyPoolService service = new ProxyPoolService(() -> {
            int run = runs.incrementAndGet();
            completedAttempts.countDown();
            if (run == 1) {
                throw new AssertionError("simulated refresh failure");
            }
        });
        service.setParallelism(1);
        service.startForTest(20);
        try {
            assertTrue(completedAttempts.await(2, TimeUnit.SECONDS),
                    "自动刷新链在第一轮异常后停止，仅执行了 " + runs.get() + " 轮");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void testAutomaticRefreshRecoversFromStuckRound() throws InterruptedException {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch firstRoundStarted = new CountDownLatch(1);
        CountDownLatch recovered = new CountDownLatch(1);
        ProxyPoolService service = new ProxyPoolService(() -> {
            int run = runs.incrementAndGet();
            if (run == 1) {
                firstRoundStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            recovered.countDown();
        }, 80);
        service.setParallelism(1);
        service.startForTest(20);
        try {
            assertTrue(firstRoundStarted.await(2, TimeUnit.SECONDS), "首轮刷新未启动");
            assertTrue(recovered.await(2, TimeUnit.SECONDS),
                    "卡死的刷新轮次没有被看门狗恢复，已执行 " + runs.get() + " 轮");
        } finally {
            service.shutdown();
        }
    }
}
