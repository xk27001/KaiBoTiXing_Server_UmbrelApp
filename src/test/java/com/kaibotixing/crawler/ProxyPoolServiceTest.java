package com.kaibotixing.crawler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
