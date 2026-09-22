package com.kaibotixing.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kaibotixing.model.Anchor;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 无依赖的 HTTP 控制台和 JSON API。静态页面内置在 fat jar 中，不依赖 CDN。
 */
public final class WebServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WebServer.class);
    private static final String APP_VERSION = "1.0.3-umbrel";
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final Pattern ANCHOR_PATH = Pattern.compile("^/api/anchors/(\\d+)$");

    private final ServerRuntime runtime;
    private final HttpServer server;
    private final ExecutorService executor;
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Map<String, byte[]> staticFiles = new ConcurrentHashMap<>();

    public WebServer(ServerRuntime runtime, String listenAddress, int port) throws IOException {
        this.runtime = runtime;
        this.server = HttpServer.create(new InetSocketAddress(listenAddress, port), 128);
        this.executor = Executors.newFixedThreadPool(8, r -> {
            Thread thread = new Thread(r, "http-server");
            thread.setDaemon(false);
            return thread;
        });
        server.createContext("/api", this::handleApi);
        server.createContext("/", this::handleStatic);
        server.setExecutor(executor);
    }

    public void start() {
        server.start();
        log.info("Web 控制台已启动: http://{}:{}/", server.getAddress().getHostString(),
                server.getAddress().getPort());
    }

    private void handleApi(HttpExchange exchange) throws IOException {
        addCommonHeaders(exchange.getResponseHeaders());
        exchange.getResponseHeaders().set("Cache-Control", "no-store");

        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if ("/api/health".equals(path)) {
                requireMethod(exchange, "GET");
                sendJson(exchange, 200, Map.of("status", "ok", "version", APP_VERSION));
            } else if ("/api/overview".equals(path)) {
                requireMethod(exchange, "GET");
                sendJson(exchange, 200, overview());
            } else if ("/api/anchors".equals(path)) {
                handleAnchors(exchange);
            } else if ("/api/logs".equals(path)) {
                handleLogs(exchange);
            } else if ("/api/sessions".equals(path)) {
                requireMethod(exchange, "GET");
                sendJson(exchange, 200, runtime.sessions(limit(exchange, 200)));
            } else if ("/api/monitor/start".equals(path)) {
                requireMethod(exchange, "POST");
                runtime.startMonitor();
                sendJson(exchange, 200, Map.of("running", true));
            } else if ("/api/monitor/stop".equals(path)) {
                requireMethod(exchange, "POST");
                runtime.stopMonitor();
                sendJson(exchange, 200, Map.of("running", false));
            } else if ("/api/settings".equals(path)) {
                handleSettings(exchange);
            } else if ("/api/proxy".equals(path)) {
                requireMethod(exchange, "GET");
                sendJson(exchange, 200, runtime.proxySnapshot());
            } else if ("/api/proxy/refresh".equals(path)) {
                requireMethod(exchange, "POST");
                sendJson(exchange, 202, Map.of("accepted", runtime.refreshProxyPool()));
            } else {
                Matcher matcher = ANCHOR_PATH.matcher(path);
                if (matcher.matches()) {
                    handleAnchorById(exchange, Long.parseLong(matcher.group(1)));
                } else {
                    sendError(exchange, 404, "接口不存在");
                }
            }
        } catch (BadRequestException e) {
            sendError(exchange, 400, e.getMessage());
        } catch (MethodNotAllowedException e) {
            exchange.getResponseHeaders().set("Allow", e.allowed);
            sendError(exchange, 405, "请求方法不允许");
        } catch (SQLException e) {
            log.error("数据库操作失败", e);
            sendError(exchange, 500, "数据库操作失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("处理 API 请求失败", e);
            sendError(exchange, 500, "服务器内部错误: " + e.getMessage());
        } finally {
            exchange.close();
        }
    }

    private Map<String, Object> overview() throws SQLException {
        List<Anchor> anchors = runtime.anchors();
        long enabled = anchors.stream().filter(Anchor::isEnabled).count();
        long live = anchors.stream()
                .filter(a -> a.getLastStatus() != null && "LIVE".equals(a.getLastStatus().name()))
                .count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("monitorRunning", runtime.isMonitorRunning());
        result.put("anchorCount", anchors.size());
        result.put("enabledAnchorCount", enabled);
        result.put("liveAnchorCount", live);
        result.put("proxy", runtime.proxySnapshot());
        result.put("settings", runtime.settingsSnapshot());
        return result;
    }

    private void handleAnchors(HttpExchange exchange) throws Exception {
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 200, runtime.anchors());
            return;
        }
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            JsonNode body = readJsonBody(exchange);
            Anchor anchor = parseAnchor(body, null);
            long id = runtime.addAnchor(anchor);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            sendJson(exchange, 201, result);
            return;
        }
        throw new MethodNotAllowedException("GET, POST");
    }

    private void handleAnchorById(HttpExchange exchange, long id) throws Exception {
        String method = exchange.getRequestMethod();
        if ("PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
            Anchor anchor = parseAnchor(readJsonBody(exchange), id);
            runtime.updateAnchor(anchor);
            sendJson(exchange, 200, Map.of("id", id));
            return;
        }
        if ("DELETE".equalsIgnoreCase(method)) {
            runtime.deleteAnchor(id);
            sendJson(exchange, 200, Map.of("id", id, "deleted", true));
            return;
        }
        throw new MethodNotAllowedException("PUT, PATCH, DELETE");
    }

    private void handleLogs(HttpExchange exchange) throws Exception {
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 200, runtime.logs(limit(exchange, 200)));
            return;
        }
        if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
            runtime.clearLogs();
            sendJson(exchange, 200, Map.of("cleared", true));
            return;
        }
        throw new MethodNotAllowedException("GET, DELETE");
    }

    private void handleSettings(HttpExchange exchange) throws Exception {
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 200, runtime.settingsSnapshot());
            return;
        }
        if ("PUT".equalsIgnoreCase(exchange.getRequestMethod()) || "PATCH".equalsIgnoreCase(exchange.getRequestMethod())) {
            JsonNode body = readJsonBody(exchange);
            int interval = requiredInt(body, "monitorIntervalSeconds", 5, 86_400);
            boolean alert = requiredBoolean(body, "alertEnabled");
            boolean log = requiredBoolean(body, "logEnabled");
            int sampleCount = requiredInt(body, "proxyValidateSampleCount", 1, 10_000);
            runtime.saveSettings(interval, alert, log, sampleCount);
            sendJson(exchange, 200, runtime.settingsSnapshot());
            return;
        }
        throw new MethodNotAllowedException("GET, PUT, PATCH");
    }

    private Anchor parseAnchor(JsonNode body, Long id) {
        Anchor anchor = new Anchor();
        if (id != null) {
            anchor.setId(id);
        }
        anchor.setNickname(requiredText(body, "nickname", 100));
        anchor.setDouyinId(requiredText(body, "douyinId", 100));
        anchor.setWebRid(optionalText(body, "webRid", 100));
        anchor.setHomeUrl(optionalText(body, "homeUrl", 500));
        anchor.setRemark(optionalText(body, "remark", 500));
        anchor.setEnabled(!body.has("enabled") || body.get("enabled").asBoolean(true));
        return anchor;
    }

    private JsonNode readJsonBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new BadRequestException("请求内容过大");
        }
        if (bytes.length == 0) {
            throw new BadRequestException("请求内容不能为空");
        }
        try {
            JsonNode body = json.readTree(bytes);
            if (body == null || !body.isObject()) {
                throw new BadRequestException("JSON 顶层必须是对象");
            }
            return body;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("JSON 格式不正确: " + e.getMessage());
        }
    }

    private int limit(HttpExchange exchange, int defaultValue) {
        String value = queryParameters(exchange.getRequestURI()).get("limit");
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Math.max(1, Math.min(Integer.parseInt(value), 500));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Map<String, String> queryParameters(URI uri) {
        Map<String, String> result = new LinkedHashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return result;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 0 || parts[0].isBlank()) {
                continue;
            }
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            result.put(key, value);
        }
        return result;
    }

    private static String requiredText(JsonNode body, String field, int maxLength) {
        String value = optionalText(body, field, maxLength);
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " 不能为空");
        }
        return value;
    }

    private static String optionalText(JsonNode body, String field, int maxLength) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText().trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > maxLength) {
            throw new BadRequestException(field + " 长度不能超过 " + maxLength);
        }
        return value;
    }

    private static int requiredInt(JsonNode body, String field, int min, int max) {
        JsonNode node = body.get(field);
        if (node == null || !node.canConvertToInt()) {
            throw new BadRequestException(field + " 必须是整数");
        }
        int value = node.asInt();
        if (value < min || value > max) {
            throw new BadRequestException(field + " 必须在 " + min + " 到 " + max + " 之间");
        }
        return value;
    }

    private static boolean requiredBoolean(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isBoolean()) {
            throw new BadRequestException(field + " 必须是布尔值");
        }
        return node.asBoolean();
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        addCommonHeaders(exchange.getResponseHeaders());
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if ("/".equals(path) || "/index.html".equals(path)) {
            path = "/index.html";
        } else if ("/favicon.ico".equals(path) || "/favicon.svg".equals(path)) {
            path = "/icon.svg";
        } else if (!List.of("/app.css", "/app.js", "/icon.svg").contains(path)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        byte[] content = staticFiles.computeIfAbsent(path, this::loadStaticFile);
        if (content == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", contentType(path));
        exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        exchange.getResponseHeaders().set("Expires", "0");
        exchange.sendResponseHeaders(200, "HEAD".equalsIgnoreCase(method) ? -1 : content.length);
        if (!"HEAD".equalsIgnoreCase(method)) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(content);
            }
        }
        exchange.close();
    }

    private byte[] loadStaticFile(String path) {
        String resource = "web" + path;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            log.warn("读取静态资源失败 {}: {}", resource, e.getMessage());
            return null;
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = json.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, Map.of("error", message == null ? "未知错误" : message));
    }

    private static void addCommonHeaders(Headers headers) {
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        headers.set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                        + "connect-src 'self'; object-src 'none'; base-uri 'none'");
    }

    private static void requireMethod(HttpExchange exchange, String method) {
        if (!method.equalsIgnoreCase(exchange.getRequestMethod())) {
            throw new MethodNotAllowedException(method);
        }
    }

    @Override
    public void close() {
        server.stop(1);
        executor.shutdownNow();
    }

    private static final class BadRequestException extends RuntimeException {
        private BadRequestException(String message) {
            super(message);
        }
    }

    private static final class MethodNotAllowedException extends RuntimeException {
        private final String allowed;

        private MethodNotAllowedException(String allowed) {
            this.allowed = allowed;
        }
    }
}
