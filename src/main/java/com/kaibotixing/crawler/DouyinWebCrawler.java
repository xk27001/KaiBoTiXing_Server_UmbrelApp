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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

    private final UserAgentProvider uaProvider;
    private final ProxyPoolService proxyPool;
    private final boolean ownsProxyPool;
    private final int timeoutSeconds;
    private final int maxRetries;
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
        this.maxRetries = Math.max(0, ConfigUtil.getInt("crawler.retry.count", 3));
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
        String roomId = anchor.getWebRid();
        String html = null;

        // 1. 优先用 web_rid 请求直播间
        if (roomId != null && !roomId.isBlank()) {
            String url = String.format(LIVE_URL_TEMPLATE, roomId.trim());
            html = fetch(url);
            if (html != null) {
                CrawlResult result = parseLivePage(html);
                if (result.status() != LiveStatus.UNKNOWN) {
                    return result;
                }
                // 解析失败则尝试主页
            }
        }

        // 2. 用 douyinId 或 homeUrl 请求主页
        String homeUrl = anchor.getHomeUrl();
        if (homeUrl == null || homeUrl.isBlank()) {
            String douyinId = anchor.getDouyinId();
            if (douyinId != null && !douyinId.isBlank()) {
                homeUrl = String.format(HOME_URL_TEMPLATE, douyinId.trim());
            }
        }
        if (homeUrl != null && !homeUrl.isBlank()) {
            String homeHtml = fetch(homeUrl);
            if (homeHtml != null) {
                if (isCaptchaPage(homeHtml)) {
                    return CrawlResult.error("主页被抖音反爬拦截，请为主播补充房间号(web_rid)");
                }
                // 主页可访问时，尝试从主页 HTML 反查 web_rid 并自动回写数据库，
                // 避免下次监控仍走主页被反爬。若反查成功且直播间页解析出有效状态，
                // 直接用直播间结果；否则再退回主页 JSON 解析作为兜底。
                String discoveredWebRid = extractWebRidFromHome(homeHtml);
                if (discoveredWebRid != null) {
                    log.info("主播「{}」从主页自动反查到房间号: {}",
                            anchor.getNickname(), discoveredWebRid);
                    String liveUrl = String.format(LIVE_URL_TEMPLATE, discoveredWebRid);
                    String liveHtml = fetch(liveUrl);
                    if (liveHtml != null) {
                        CrawlResult liveResult = parseLivePage(liveHtml);
                        if (liveResult.status() != LiveStatus.UNKNOWN) {
                            return CrawlResult.ofWithDiscoveredWebRid(
                                    liveResult.status(), liveResult.roomId(), discoveredWebRid);
                        }
                    }
                }
                return parseHomePage(homeHtml);
            }
        }

        return CrawlResult.error("未获取到可解析的页面内容");
    }

    /**
     * 抓取页面内容，带随机 UA 与代理轮换重试：
     * <ul>
     *   <li>每次尝试随机选取 UA；正常请求池内随机取代理（可用代理被重复使用）；</li>
     *   <li>失败后换一个与上次不同的代理重试（排除刚用过的），最多 {@link #maxRetries} 次，多换 IP 多试几次；</li>
     *   <li>仅收到明确反爬信号（503/403/429）时剔除当前代理；普通超时/连接抖动不剔除，保留可用 IP 重复使用。</li>
     * </ul>
     */
    private String fetch(String url) {
        Exception lastError = null;
        int lastStatus = -1;
        Proxy lastProxy = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            String ua = uaProvider.random();
            // 重试时排除刚用过的代理，换新 IP；首次(lastProxy=null)池内随机
            Proxy proxy = proxyPool.next(lastProxy);
            lastProxy = proxy;
            HttpClient httpClient = (proxy != null) ? proxyPool.clientFor(proxy) : directClient;
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .header("User-Agent", ua)
                        .header("Accept", "text/html,application/xhtml+xml,application/json")
                        .header("Accept-Language", "zh-CN,zh;q=0.9")
                        .header("Referer", "https://www.douyin.com/")
                        .GET()
                        .build();
                HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code == 200) {
                    return resp.body();
                }
                lastStatus = code;
                lastError = new IOException("HTTP " + code);
                if (code == 503 || code == 403 || code == 429) {
                    // 明确反爬/限流：剔除当前代理，换代理重试
                    if (proxy != null) {
                        proxyPool.markFailed(proxy);
                    }
                    log.warn("请求 {} 返回 {}（第 {} 次），已剔除代理 {}，换代理重试",
                            url, code, attempt + 1, proxy);
                    continue;
                }
                // 其他状态码（如 404）重试意义不大，直接失败
                log.warn("请求 {} 返回状态码 {}", url, code);
                return null;
            } catch (Exception e) {
                lastError = e;
                // 普通超时/连接抖动：不剔除，仅换一个代理再试（保留可用 IP）
                log.warn("请求 {} 第 {} 次失败: {}（代理 {}）", url, attempt + 1, e.getMessage(), proxy);
            }
        }
        log.warn("请求 {} 重试 {} 次后仍失败，最后错误: {}，最后状态码: {}", url, maxRetries,
                lastError != null ? lastError.getMessage() : "未知", lastStatus);
        return null;
    }

    /**
     * 判断返回内容是否为抖音验证码/验证中间页。
     */
    private boolean isCaptchaPage(String html) {
        if (html == null) {
            return false;
        }
        return html.contains("TTGCaptcha")
                || html.contains("验证中间页")
                || html.contains("captcha")
                || html.contains("verify")
                || (html.length() < 10000 && !html.contains("roomId") && !html.contains("__pace_f"));
    }

    /**
     * 解析直播间页面，提取 room status 与 roomId。
     */
    private CrawlResult parseLivePage(String html) {
        JsonNode json = extractRouterData(html);
        if (json != null) {
            CrawlResult result = resolveFromJson(json);
            if (result.status() != LiveStatus.UNKNOWN) {
                return result;
            }
        }
        return parsePaceData(html);
    }

    private CrawlResult parseHomePage(String html) {
        JsonNode json = extractRouterData(html);
        if (json != null) {
            CrawlResult result = resolveFromJson(json);
            if (result.status() != LiveStatus.UNKNOWN) {
                return result;
            }
        }
        return parsePaceData(html);
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
