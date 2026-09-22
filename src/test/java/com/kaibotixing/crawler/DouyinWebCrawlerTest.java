package com.kaibotixing.crawler;

import com.kaibotixing.model.LiveStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抖音爬取器解析逻辑单元测试（使用固定 HTML 片段，不依赖网络）。
 */
class DouyinWebCrawlerTest {

    @Test
    void testCaptchaPageDetection() throws Exception {
        DouyinWebCrawler crawler = new DouyinWebCrawler();
        var method = DouyinWebCrawler.class.getDeclaredMethod("isCaptchaPage", String.class);
        method.setAccessible(true);

        assertTrue((Boolean) method.invoke(crawler, "<html>TTGCaptcha</html>"));
        assertTrue((Boolean) method.invoke(crawler, "<html>验证中间页</html>"));
        assertTrue((Boolean) method.invoke(crawler, "<html><div id=\"captcha-container\"></div></html>"));
    }
    @Test
    void testLiveStatusFrom() {
        assertEquals(LiveStatus.LIVE, LiveStatus.from("LIVE"));
        assertEquals(LiveStatus.OFFLINE, LiveStatus.from("OFFLINE"));
        assertEquals(LiveStatus.UNKNOWN, LiveStatus.from("UNKNOWN"));
        assertEquals(LiveStatus.UNKNOWN, LiveStatus.from(null));
        assertEquals(LiveStatus.UNKNOWN, LiveStatus.from("invalid"));
    }

    @Test
    void testParsePayloadWithIPrefix() throws Exception {
        DouyinWebCrawler crawler = new DouyinWebCrawler();
        // 模拟真实 pace_f 载荷：JSON 内嵌在 JS 字符串中，含双重转义
        // 实际值形如 1:I{\\\"roomId\\\":\\\"1234567890\\\"}
        String payload = "1:I{\\\"roomId\\\":\\\"1234567890\\\",\\\"name\\\":\\\"test\\\"}";
        var method = DouyinWebCrawler.class.getDeclaredMethod("parsePacePayload", String.class);
        method.setAccessible(true);
        Object result = method.invoke(crawler, payload);
        assertNotNull(result);
        assertTrue(result.toString().contains("1234567890"));
    }

    @Test
    void testUnescapeJsonString() throws Exception {
        DouyinWebCrawler crawler = new DouyinWebCrawler();
        var method = DouyinWebCrawler.class.getDeclaredMethod("unescapeJsonString", String.class);
        method.setAccessible(true);
        // 输入含转义引号 \\\" -> 输出 "
        String input = "{\\\"roomId\\\":\\\"123\\\"}";
        String output = (String) method.invoke(crawler, input);
        assertEquals("{\"roomId\":\"123\"}", output);
    }

    @Test
    void testResolveLiveWhenRoomStatus2() throws Exception {
        DouyinWebCrawler crawler = new DouyinWebCrawler();
        // room.status=2 => 开播（实际抖音开播页面结构）
        String payload = "2:[{\\\"room\\\":{\\\"id_str\\\":\\\"7674867629881936666\\\",\\\"status\\\":2,\\\"title\\\":\\\"test\\\"}}]";
        var method = DouyinWebCrawler.class.getDeclaredMethod("parsePacePayload", String.class);
        method.setAccessible(true);
        Object json = method.invoke(crawler, payload);
        assertNotNull(json);

        var resolve = DouyinWebCrawler.class.getDeclaredMethod("resolveFromJson",
                com.fasterxml.jackson.databind.JsonNode.class);
        resolve.setAccessible(true);
        CrawlResult result = (CrawlResult) resolve.invoke(crawler, json);
        assertEquals(LiveStatus.LIVE, result.status());
        assertEquals("7674867629881936666", result.roomId());
    }

    @Test
    void testResolveOfflineWhenRoomStatus4() throws Exception {
        DouyinWebCrawler crawler = new DouyinWebCrawler();
        // room.status=4 => 未开播
        String payload = "2:[{\\\"room\\\":{\\\"id_str\\\":\\\"7674867629881936666\\\",\\\"status\\\":4}}]";
        var method = DouyinWebCrawler.class.getDeclaredMethod("parsePacePayload", String.class);
        method.setAccessible(true);
        Object json = method.invoke(crawler, payload);
        assertNotNull(json);

        var resolve = DouyinWebCrawler.class.getDeclaredMethod("resolveFromJson",
                com.fasterxml.jackson.databind.JsonNode.class);
        resolve.setAccessible(true);
        CrawlResult result = (CrawlResult) resolve.invoke(crawler, json);
        assertEquals(LiveStatus.OFFLINE, result.status());
    }

    @Test
    void testResolveUnknownWhenNoRoomInfo() throws Exception {
        DouyinWebCrawler crawler = new DouyinWebCrawler();
        // 无 room 对象、无有效 roomId => 未知
        String payload = "2:[{\\\"roomId\\\":\\\"$undefined\\\"}]";
        var method = DouyinWebCrawler.class.getDeclaredMethod("parsePacePayload", String.class);
        method.setAccessible(true);
        Object json = method.invoke(crawler, payload);
        assertNotNull(json);

        var resolve = DouyinWebCrawler.class.getDeclaredMethod("resolveFromJson",
                com.fasterxml.jackson.databind.JsonNode.class);
        resolve.setAccessible(true);
        CrawlResult result = (CrawlResult) resolve.invoke(crawler, json);
        assertEquals(LiveStatus.UNKNOWN, result.status());
    }

    @Test
    void testResolveLiveWhenRoomIdNumeric() throws Exception {
        DouyinWebCrawler crawler = new DouyinWebCrawler();
        // 兜底：roomId 为有效数字 => 开播
        String payload = "2:[{\\\"roomId\\\":\\\"1234567890\\\"}]";
        var method = DouyinWebCrawler.class.getDeclaredMethod("parsePacePayload", String.class);
        method.setAccessible(true);
        Object json = method.invoke(crawler, payload);
        assertNotNull(json);

        var resolve = DouyinWebCrawler.class.getDeclaredMethod("resolveFromJson",
                com.fasterxml.jackson.databind.JsonNode.class);
        resolve.setAccessible(true);
        CrawlResult result = (CrawlResult) resolve.invoke(crawler, json);
        assertEquals(LiveStatus.LIVE, result.status());
        assertEquals("1234567890", result.roomId());
    }
}
