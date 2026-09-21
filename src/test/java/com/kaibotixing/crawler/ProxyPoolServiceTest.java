package com.kaibotixing.crawler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProxyPoolServiceTest {

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
