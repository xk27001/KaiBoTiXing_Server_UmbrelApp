package com.kaibotixing.crawler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaibotixing.model.Anchor;
import com.kaibotixing.model.LiveStatus;
import com.kaibotixing.util.ConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于抖音 Web 端的直播状态爬取器。
 * <p>
 * 通过请求直播间页面（https://live.douyin.com/{web_rid}）解析内嵌 JSON 中的
 * room status（2=直播中，4=未开播），无 web_rid 时尝试解析主页 URL。
 * 抖音反爬严格且页面结构易变，解析失败统一返回 UNKNOWN，不抛出中断监控。
 * </p>
 */
public class DouyinWebCrawler implements DouyinCrawler {

    private static final Logger log = LoggerFactory.getLogger(DouyinWebCrawler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String LIVE_URL_TEMPLATE = "https://live.douyin.com/%s";
    private static final String HOME_URL_TEMPLATE = "https://www.douyin.com/user/%s";

    // 匹配直播间页内嵌的 window._ROUTER_DATA JSON
    private static final Pattern ROUTER_DATA = Pattern.compile(
            "window\\._ROUTER_DATA\\s*=\\s*(\\{.*?})\\s*</script>", Pattern.DOTALL);
    // 匹配主播主页中嵌入的直播间链接，用于自动反查 web_rid
    // 抖音个人主页含 "live.douyin.com/19xxxxxxxxxxxxxxxxxx" 形式的直播间房间号
    private static final Pattern LIVE_RID_FROM_HOME = Pattern.compile("live\\.douyin\\.com/(\\d{10,25})");
    private static final Pattern RENDER_DATA = Pattern.compile("id=\"RENDER_DATA\"[^>]*>(.*?)</script>", Pattern.DOTALL);
    private static final Pattern INITIAL_STATE = Pattern.compile("(?:window\\.)?__INITIAL_STATE__\\s*=\\s*", Pattern.DOTALL);
    private static final Pattern ROOM_STATUS = Pattern.compile("\"room\"\\s*:\\s*\\{.{0,5000}?\"status\"\\s*:\\s*(\\d+)", Pattern.DOTALL);

    private final UserAgentProvider uaProvider;
    private final ProxyPoolService proxyPool;
    private final boolean ownsProxyPool;
    private final int timeoutSeconds;
    private final int maxAttempts;
    /** 直连 HttpClient（代理池为空时的兜底） */
    private final HttpClient directClient;

    /** 默认构造：自建并启动代理池（保持现有调用方式兼容）。 */
    public DouyinWebCrawler() {
        this(null);
    }

    /**
     * 注入构造：接收外部代理池实例（生命周期由调用方管理，如 MainController）。
     *
     * @param proxyPool 代理池实例；传 null 时自建并启动
     */
    public DouyinWebCrawler(ProxyPoolService proxyPool) {
        this.timeoutSeconds = ConfigUtil.getInt("crawler.timeout.seconds", 10);
        this.maxAttempts = Math.min(50, Math.max(2, ConfigUtil.getInt("crawler.retry.count", 15)));
        this.uaProvider = new UserAgentProvider();
        if (proxyPool != null) {
            this.proxyPool = proxyPool;
            this.ownsProxyPool = false;
        } else {
            this.proxyPool = new ProxyPoolService();
            this.proxyPool.start();
            this.ownsProxyPool = true;
        }
        this.directClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
    }

    @Override
    public CrawlResult crawl(Anchor anchor) {
        String lastError = null;
        String roomId = anchor.getWebRid();

        // 1. 优先用 web_rid 请求直播间。验证码页会自动换代理重试。
        if (roomId != null && !roomId.isBlank()) {
            String url = String.format(LIVE_URL_TEMPLATE, roomId.trim());
            FetchResult liveFetch = fetch(url);
            if (liveFetch.html() != null) {
                CrawlResult result = parseLivePage(liveFetch.html());
                if (result.status() != LiveStatus.UNKNOWN) {
                    return result;
                }
                lastError = result.errorMsg();
            } else {
                lastError = liveFetch.error();
            }
        }

        // 2. 用 douyinId 或 homeUrl 请求主页。
        String homeUrl = anchor.getHomeUrl();
        if (homeUrl == null || homeUrl.isBlank()) {
            String douyinId = anchor.getDouyinId();
            if (douyinId != null && !douyinId.isBlank()) {
                homeUrl = String.format(HOME_URL_TEMPLATE, douyinId.trim());
            }
        }

        if (homeUrl != null && !homeUrl.isBlank()) {
            FetchResult homeFetch = fetch(homeUrl);
            if (homeFetch.html() != null) {
                String homeHtml = homeFetch.html();
                String discoveredWebRid = extractWebRidFromHome(homeHtml);
                if (discoveredWebRid != null) {
                    log.info("主播「{}」从主页自动反查到房间号: {}",
                            anchor.getNickname(), discoveredWebRid);
                    String liveUrl = String.format(LIVE_URL_TEMPLATE, discoveredWebRid);
                    FetchResult liveFetch = fetch(liveUrl);
                    if (liveFetch.html() != null) {
                        CrawlResult liveResult = parseLivePage(liveFetch.html());
                        if (liveResult.status() != LiveStatus.UNKNOWN) {
                            return CrawlResult.ofWithDiscoveredWebRid(
                                    liveResult.status(), liveResult.roomId(), discoveredWebRid);
                        }
                        lastError = liveResult.errorMsg();
                    } else {
                        lastError = liveFetch.error();
                    }
                }

                CrawlResult homeResult = parseHomePage(homeHtml);
                if (homeResult.status() != LiveStatus.UNKNOWN) {
                    return homeResult;
                }
                lastError = homeResult.errorMsg();
            } else {
                lastError = homeFetch.error();
            }
        }

        return CrawlResult.error(lastError != null ? lastError : "未获取到可解析的页面内容");
    }

    /**
     * 抓取页面内容。验证码页、明确反爬状态码和空内容都会触发代理轮换；
     * 所有代理失败后还会额外尝试一次直连。
     */
    private FetchResult fetch(String url) {
        Exception lastError = null;
        boolean lastWasCaptcha = false;
        boolean usedProxy = false;
        Set<Proxy> attemptedProxies = new HashSet<>();

        boolean hasAvailableProxy = proxyPool.getStatus().available() > 0;
        int proxyAttempts = hasAvailableProxy ? Math.max(1, maxAttempts - 1) : maxAttempts;
        for (int attempt = 0; attempt < proxyAttempts; attempt++) {
            String ua = uaProvider.random();
            Proxy proxy = proxyPool.nextExcluding(attemptedProxies);
            if (proxy != null) {
                attemptedProxies.add(proxy);
            }
            usedProxy = usedProxy || proxy != null;
            try {
                HttpResponse<String> response = sendRequest(url, proxy, ua);
                int code = response.statusCode();
                if (code == 200) {
                    String body = response.body();
                    if (!isCaptchaPage(body)) {
                        return new FetchResult(body, null);
                    }
                    lastWasCaptcha = true;
                    lastError = new IOException("抖音验证页");
                    if (proxy != null) {
                        proxyPool.markFailed(proxy);
                    }
                    log.warn("请求 {} 命中抖音验证页（第 {} 次），剔除代理 {}，继续换代理", url,
                            attempt + 1, proxy);
                    continue;
                }

                lastWasCaptcha = false;
                lastError = new IOException("HTTP " + code);
                if (code == 503 || code == 403 || code == 429) {
                    if (proxy != null) {
                        proxyPool.markFailed(proxy);
                    }
                    log.warn("请求 {} 返回 {}（第 {} 次），剔除代理 {}，继续换代理",
                            url, code, attempt + 1, proxy);
                    continue;
                }

                log.warn("请求 {} 返回状态码 {}", url, code);
                return new FetchResult(null, "请求失败，HTTP 状态码 " + code);
            } catch (Exception e) {
                lastWasCaptcha = false;
                lastError = e;
                log.warn("请求 {} 第 {} 次失败: {}（代理 {}）",
                        url, attempt + 1, e.getMessage(), proxy);
            }
        }

        // 代理池可用但全部被验证码/限流时，再尝试一次直连。
        if (usedProxy) {
            try {
                HttpResponse<String> direct = sendRequest(url, null, uaProvider.random());
                if (direct.statusCode() == 200) {
                    if (!isCaptchaPage(direct.body())) {
                        return new FetchResult(direct.body(), null);
                    }
                    lastWasCaptcha = true;
                    lastError = new IOException("直连命中抖音验证页");
                } else {
                    lastWasCaptcha = false;
                    lastError = new IOException("直连 HTTP " + direct.statusCode());
                }
            } catch (Exception e) {
                lastWasCaptcha = false;
                lastError = e;
            }
        }

        String error = lastWasCaptcha
                ? "所有可用代理及直连均返回抖音验证页，请稍后重试或补充正确的 web_rid"
                : "请求失败: " + (lastError == null ? "未知错误" : lastError.getMessage());
        log.warn("请求 {} 最多尝试 {} 次后仍失败: {}", url, maxAttempts, error);
        return new FetchResult(null, error);
    }

    private HttpResponse<String> sendRequest(String url, Proxy proxy, String userAgent) throws Exception {
        HttpClient httpClient = proxy != null ? proxyPool.clientFor(proxy) : directClient;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/json")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Referer", "https://www.douyin.com/")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private record FetchResult(String html, String error) {
    }

    /**
     * 判断返回内容是否为抖音验证码/验证中间页。
     */
    private boolean isCaptchaPage(String html) {
        if (html == null) {
            return false;
        }
        String lower = html.toLowerCase(Locale.ROOT);
        return lower.contains("ttgcaptcha")
                || html.contains("验证中间页")
                || lower.contains("captcha_verify")
                || lower.contains("secsdk-captcha")
                || lower.contains("captcha-container")
                || lower.contains("verify-bar")
                || (html.length() < 50_000 && (lower.contains("captcha") || lower.contains("verify")))
                || (html.length() < 10000 && !html.contains("roomId") && !html.contains("__pace_f"));
    }

    /**
     * 解析直播间页面，依次支持 _ROUTER_DATA、__pace_f、RENDER_DATA、__INITIAL_STATE__ 和文本状态兜底。
     */
    private CrawlResult parseLivePage(String html) {
        CrawlResult result = parseRouterData(html);
        if (result.status() != LiveStatus.UNKNOWN) {
            return result;
        }
        result = parsePaceData(html);
        if (result.status() != LiveStatus.UNKNOWN) {
            return result;
        }
        result = parseRenderData(html);
        if (result.status() != LiveStatus.UNKNOWN) {
            return result;
        }
        result = parseInitialStateData(html);
        if (result.status() != LiveStatus.UNKNOWN) {
            return result;
        }
        result = parseRoomStatusFromText(html);
        if (result.status() != LiveStatus.UNKNOWN) {
            return result;
        }
        return CrawlResult.error("未能从页面提取直播状态（长度 " + html.length()
                + "，roomId=" + html.contains("roomId")
                + "，pace=" + html.contains("__pace_f") + "）");
    }

    private CrawlResult parseHomePage(String html) {
        CrawlResult result = parseRouterData(html);
        if (result.status() != LiveStatus.UNKNOWN) {
            return result;
        }
        result = parsePaceData(html);
        if (result.status() != LiveStatus.UNKNOWN) {
            return result;
        }
        result = parseRenderData(html);
        if (result.status() != LiveStatus.UNKNOWN) {
            return result;
        }
        result = parseInitialStateData(html);
        if (result.status() != LiveStatus.UNKNOWN) {
            return result;
        }
        result = parseRoomStatusFromText(html);
        return result.status() != LiveStatus.UNKNOWN ? result : CrawlResult.error("未能从页面提取直播状态");
    }

    private CrawlResult parseRouterData(String html) {
        JsonNode json = extractRouterData(html);
        return json == null ? CrawlResult.error("页面中没有 _ROUTER_DATA") : resolveFromJson(json);
    }

    private CrawlResult parseRenderData(String html) {
        Matcher matcher = RENDER_DATA.matcher(html);
        if (!matcher.find()) {
            return CrawlResult.error("页面中没有 RENDER_DATA");
        }
        try {
            String decoded = URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
            return resolveFromJson(MAPPER.readTree(decoded));
        } catch (Exception e) {
            log.debug("解析 RENDER_DATA 失败: {}", e.getMessage());
            return CrawlResult.error("RENDER_DATA 解析失败");
        }
    }

    private CrawlResult parseInitialStateData(String html) {
        Matcher matcher = INITIAL_STATE.matcher(html);
        if (!matcher.find()) {
            return CrawlResult.error("页面中没有 __INITIAL_STATE__");
        }
        int jsonStart = html.indexOf('{', matcher.end());
        if (jsonStart < 0) {
            return CrawlResult.error("__INITIAL_STATE__ 格式异常");
        }
        try {
            String jsonText = extractBalancedJson(html, jsonStart);
            return resolveFromJson(MAPPER.readTree(jsonText));
        } catch (Exception e) {
            log.debug("解析 __INITIAL_STATE__ 失败: {}", e.getMessage());
            return CrawlResult.error("__INITIAL_STATE__ 解析失败");
        }
    }

    /** 提取从起始符号开始的完整 JSON 对象/数组，正确处理字符串中的括号。 */
    private String extractBalancedJson(String text, int start) {
        char open = text.charAt(start);
        char close = open == '{' ? '}' : ']';
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    quoted = false;
                }
                continue;
            }
            if (c == '"') {
                quoted = true;
            } else if (c == open) {
                depth++;
            } else if (c == close && --depth == 0) {
                return text.substring(start, i + 1);
            }
        }
        throw new IllegalArgumentException("JSON 对象未闭合");
    }

    /** 对转义后的页面文本做状态兜底，适配新版抖音字段。 */
    private CrawlResult parseRoomStatusFromText(String html) {
        String normalized = html.replace("\\\"", "\"").replace("\\\\", "\\");
        Matcher statusMatcher = ROOM_STATUS.matcher(normalized);
        if (statusMatcher.find()) {
            int status = Integer.parseInt(statusMatcher.group(1));
            return CrawlResult.of(status == 2 ? LiveStatus.LIVE : LiveStatus.OFFLINE, null);
        }
        if (normalized.contains("\"roomId\":\"$undefined\"")
                || normalized.contains("\"room_status\":4")
                || normalized.contains("\"live_status\":4")
                || normalized.contains("\"is_live\":false")
                || html.contains("直播已结束")) {
            return CrawlResult.of(LiveStatus.OFFLINE, null);
        }
        if (normalized.contains("\"flv_pull_url\"")
                || normalized.contains("\"hls_pull_url\"")
                || normalized.contains("\"user_count_str\"")) {
            return CrawlResult.of(LiveStatus.LIVE, null);
        }
        return CrawlResult.error("页面文本中没有直播状态");
    }
    /**
     * 解析 React SSR 的 __pace_f.push([1,"..."]) 数据。
     * 每个数据块格式为 {@code <hex>:I{json}}，需要去掉前缀、反转义后再解析。
     * 使用字符串查找而非正则，避免长 HTML 上的灾难性回溯。
     */
    private CrawlResult parsePaceData(String html) {
        final String marker = "__pace_f.push([1,\"";
        int searchFrom = 0;
        while (true) {
            int start = html.indexOf(marker, searchFrom);
            if (start < 0) {
                break;
            }
            int contentStart = start + marker.length();
            int end = html.indexOf("\"])", contentStart);
            if (end < 0) {
                break;
            }
            String raw = html.substring(contentStart, end);
            searchFrom = end + 3;

            try {
                JsonNode json = parsePacePayload(raw);
                if (json != null) {
                    CrawlResult result = resolveFromJson(json);
                    if (result.status() != LiveStatus.UNKNOWN) {
                        return result;
                    }
                }
            } catch (Exception e) {
                log.debug("解析 pace_f 数据块失败: {}", e.getMessage());
            }
        }
        return CrawlResult.error("未能从页面提取直播状态");
    }

    /**
     * 解析单个 pace_f 载荷：去除 {@code <hex>:I} 或 {@code <hex>:[} 前缀，反转义 JSON 字符串。
     */
    private JsonNode parsePacePayload(String raw) throws Exception {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String payload = raw;
        // 去除前缀：找到 ":" 后紧跟 "I" 或 "[" 或 "{" 的位置，去掉 ":" 及其前缀
        int idx = payload.indexOf(":I");
        int skip = 2; // ":I" 占 2 字符
        if (idx < 0) {
            idx = payload.indexOf(":[");
            skip = 1;
        }
        if (idx < 0) {
            idx = payload.indexOf(":{");
            skip = 1;
        }
        if (idx >= 0) {
            payload = payload.substring(idx + skip);
        }
        // 反转义（页面里 JSON 是字符串内嵌的，含转义引号和反斜杠u十六进制转义）
        String unescaped = unescapeJsonString(payload);
        return MAPPER.readTree(unescaped);
    }

    /**
     * 反转义内嵌 JSON 字符串：\\" -> "，\\n -> 换行，\\uXXXX -> Unicode 字符，
     * 并移除末尾可能存在的多余反斜杠转义。
     */
    private String unescapeJsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 5 < s.length()) {
                            try {
                                int code = Integer.parseInt(s.substring(i + 2, i + 6), 16);
                                sb.append((char) code);
                                i += 4;
                            } catch (NumberFormatException e) {
                                sb.append("\\u");
                                i += 1;
                            }
                        }
                    }
                    default -> sb.append(next);
                }
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * 从 JSON 树中查找房间状态并判断开播状态。
     * <p>
     * 抖音直播间页面结构（实测）：
     * 开播时存在 {@code room} 对象，形如
     * {@code {"id_str":"7674867629881936666","status":2,"title":"...","user_count_str":"34"}}，
     * 其中 {@code status=2} 表示直播中；未开播时该 room 对象不存在（仅有
     * {@code roomId:"$undefined"} 占位）。因此优先以 room.status 判断。
     * </p>
     */
    private CrawlResult resolveFromJson(JsonNode json) {
        // 1. 优先查找 room 对象（同时含 id_str 与 status 字段）
        JsonNode room = findRoomObject(json);
        if (room != null) {
            String idStr = room.has("id_str") && room.get("id_str").isTextual()
                    ? room.get("id_str").asText() : null;
            int status = room.get("status").asInt();
            if (status == 2) {
                return CrawlResult.of(LiveStatus.LIVE, idStr);
            }
            // status 为其他值（如 4）=> 未开播
            return CrawlResult.of(LiveStatus.OFFLINE, idStr);
        }

        // 2. 兜底：有效 roomId 数字 => 开播
        String roomId = findValidRoomId(json);
        if (roomId != null) {
            return CrawlResult.of(LiveStatus.LIVE, roomId);
        }

        // 3. 兜底：存在开播特征字段（拉流地址/观众数）=> 开播
        if (hasLiveMarker(json)) {
            return CrawlResult.of(LiveStatus.LIVE, null);
        }

        return CrawlResult.error("页面中未找到直播状态信息");
    }

    /**
     * 递归查找 room 对象：同时包含 id_str（文本）和 status（整数）字段的对象。
     */
    private JsonNode findRoomObject(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            if (node.has("status") && node.get("status").isInt()
                    && node.has("id_str") && node.get("id_str").isTextual()) {
                return node;
            }
            for (JsonNode child : node) {
                JsonNode found = findRoomObject(child);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findRoomObject(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 递归查找有效的 roomId（数字字符串，长度至少 6 位，避免误匹配短 id）。
     */
    private String findValidRoomId(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            JsonNode rid = node.get("roomId");
            if (rid != null && rid.isTextual() && isNumeric(rid.asText())
                    && rid.asText().length() >= 6) {
                return rid.asText();
            }
            for (JsonNode child : node) {
                String found = findValidRoomId(child);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String found = findValidRoomId(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 判断是否存在开播特征字段（flv 拉流地址或观众数），作为兜底判断。
     */
    private boolean hasLiveMarker(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            if (node.has("flv_pull_url")) {
                return true;
            }
            if (node.has("user_count_str") && node.get("user_count_str").isTextual()
                    && isNumeric(node.get("user_count_str").asText())) {
                return true;
            }
            for (JsonNode child : node) {
                if (hasLiveMarker(child)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (hasLiveMarker(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isNumeric(String s) {
        return s != null && !s.isEmpty() && !"$undefined".equals(s)
                && s.chars().allMatch(Character::isDigit);
    }

    private JsonNode extractRouterData(String html) {
        Matcher m = ROUTER_DATA.matcher(html);
        if (m.find()) {
            try {
                return MAPPER.readTree(m.group(1));
            } catch (Exception e) {
                log.debug("解析 _ROUTER_DATA 失败: {}", e.getMessage());
            }
        }
        return null;
    }

    /**
     * 从主播主页 HTML 中提取直播间链接对应的 web_rid。
     * 主页中 "live.douyin.com/{19 位数字}" 即为该主播的直播间房间号，
     * 用于在主播未配置 web_rid 时自动反查，避免每次爬取都走主页被反爬拦截。
     *
     * @return 第一个匹配的房间号；未匹配返回 null
     */
    private String extractWebRidFromHome(String html) {
        if (html == null || html.isEmpty()) {
            return null;
        }
        Matcher m = LIVE_RID_FROM_HOME.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    /** 关闭内部自建的代理池（外部注入的实例由调用方管理生命周期）。 */
    public void shutdown() {
        if (ownsProxyPool) {
            proxyPool.shutdown();
        }
    }
}
