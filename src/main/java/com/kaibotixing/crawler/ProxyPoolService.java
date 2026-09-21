package com.kaibotixing.crawler;

import com.kaibotixing.util.ConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * 免费代理池：定时从多个公开代理列表源拉取 {@code ip:port}，并发连通性验证后
 * 保留可用代理，供爬取器随机轮换使用；失败代理即时剔除，下次刷新重新验证拉回。
 * <p>
 * 数据源默认托管在 GitHub（raw.githubusercontent.com），国内网络无法直连，
 * 因此拉取时会对每个源并行尝试「直连 + 多个国内可用镜像前缀 + jsDelivr CDN」候选 URL，
 * 任一成功即可。全程失败不影响主流程：代理池为空时调用方走直连兜底。
 * 所有公共方法对并发访问安全，拉取/验证/刷新均在独立后台线程执行。
 * </p>
 */
public class ProxyPoolService {

    private static final Logger log = LoggerFactory.getLogger(ProxyPoolService.class);

    /** 默认代理数据源（GitHub 公共代理列表，一行一个 ip:port） */
    private static final String DEFAULT_SOURCES = String.join(",",
            "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt",
            "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt",
            "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/http/data.txt");

    /**
     * 默认 GitHub raw 加速镜像前缀（2026-08 实测可用；空串表示直连）。
     * 拉取时每个源依次尝试「原始 URL + 各镜像前缀 + jsDelivr CDN」。
     */
    private static final String DEFAULT_MIRROR_PREFIXES = String.join(",",
            "",                                  // 直连
            "https://github.akams.cn/",
            "https://gh-proxy.com/",
            "https://ghproxy.net/",
            "https://ghproxy.homeboyc.cn/");

    /** 默认代理连通性验证目标（国内可达的轻量页面；自动补充 HTTP 版备选） */
    private static final String DEFAULT_VALIDATE_URL = "https://www.baidu.com";

    private static final Pattern IP_PORT = Pattern.compile(
            "^\\d{1,3}(\\.\\d{1,3}){3}:\\d{2,5}$");

    /** 拉取代理源与直连请求的 UA */
    private static final String FETCH_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private final boolean enabled;
    private final List<String> sources;
    private final List<String> mirrorPrefixes;
    private final long refreshMinutes;
    private final int validateTimeoutSeconds;
    private final String validateUrl;
    private final String validateUrlBackup;
    private final int maxCount;
    /** 每轮实际采样验证的候选条数，可由 Web 控制台持久化配置 */
    private volatile int validateSampleCount;
    private final int parallelism;
    private final int fetchTimeoutSeconds;
    private final int requestTimeoutSeconds;

    /** 可用代理池（随机轮换使用） */
    private final CopyOnWriteArrayList<Proxy> available = new CopyOnWriteArrayList<>();
    /** 按代理缓存的 HttpClient，复用连接 */
    private final ConcurrentHashMap<Proxy, HttpClient> proxyClients = new ConcurrentHashMap<>();
    /** 代理被选取次数，用于「最少使用优先」轮换，保证池大时每个代理都被均匀使用 */
    private final ConcurrentHashMap<Proxy, java.util.concurrent.atomic.AtomicInteger> usageCount = new ConcurrentHashMap<>();
    /** 直连 HttpClient 单例（拉取数据源 / 兜底请求） */
    private final HttpClient directClient;

    /** 处理过程事件日志（环形缓冲，供代理池详情窗口展示），超出上限丢弃最旧 */
    private final CopyOnWriteArrayList<String> eventLog = new CopyOnWriteArrayList<>();
    private static final int EVENT_LOG_LIMIT = 500;
    private static final DateTimeFormatter EVENT_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** 连通性验证进度（已验证数 / 本轮总数），未在验证时 total=0 */
    private final java.util.concurrent.atomic.AtomicInteger validatedDone = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile int validateTotal;
    /** 手动刷新防重入标志（刷新排队中也视为进行中） */
    private final java.util.concurrent.atomic.AtomicBoolean refreshPending = new java.util.concurrent.atomic.AtomicBoolean(false);

    private volatile ScheduledExecutorService scheduler;
    private volatile ExecutorService workerPool;
    private volatile LocalDateTime lastRefresh;
    private volatile int lastCandidateCount;

    /** 代理池状态快照，供 UI 展示 */
    public record ProxyStatus(boolean enabled, int available, int candidateCount, LocalDateTime lastRefresh) {
    }

    public ProxyPoolService() {
        this.enabled = ConfigUtil.getBoolean("crawler.proxy.enabled", true);
        List<String> configuredSources = parseList(ConfigUtil.get("crawler.proxy.sources", ""));
        this.sources = configuredSources.isEmpty() ? parseList(DEFAULT_SOURCES) : configuredSources;
        List<String> configuredMirrors = parseList(ConfigUtil.get("crawler.proxy.mirror.prefixes", ""));
        this.mirrorPrefixes = configuredMirrors.isEmpty() ? parseList(DEFAULT_MIRROR_PREFIXES) : configuredMirrors;
        this.refreshMinutes = Math.max(1, ConfigUtil.getInt("crawler.proxy.refresh.minutes", 30));
        this.validateTimeoutSeconds = Math.max(1, ConfigUtil.getInt("crawler.proxy.validate.timeout.seconds", 8));
        this.validateUrl = ConfigUtil.get("crawler.proxy.validate.url", DEFAULT_VALIDATE_URL);
        this.validateUrlBackup = toHttp(this.validateUrl);
        this.maxCount = Math.max(1, ConfigUtil.getInt("crawler.proxy.max.count", 50));
        int sampleFactor = Math.max(1, ConfigUtil.getInt("crawler.proxy.validate.sample.factor", 5));
        int defaultSampleCount = maxCount * sampleFactor;
        this.validateSampleCount = clampSampleCount(
                ConfigUtil.getInt("crawler.proxy.validate.sample.count", defaultSampleCount));
        this.parallelism = Math.max(1, ConfigUtil.getInt("crawler.proxy.validate.parallelism", 40));
        this.requestTimeoutSeconds = Math.max(3, ConfigUtil.getInt("crawler.timeout.seconds", 10));
        this.fetchTimeoutSeconds = 15;
        this.directClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(fetchTimeoutSeconds))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
    }

    /** 当前每轮采样验证条数。 */
    public int getValidateSampleCount() {
        return validateSampleCount;
    }

    /** 更新每轮采样验证条数（1-10000）。 */
    public void setValidateSampleCount(int count) {
        this.validateSampleCount = clampSampleCount(count);
    }

    private static int clampSampleCount(int count) {
        return Math.max(1, Math.min(count, 10_000));
    }
    /** 逗号分隔字符串 -> 去空格列表 */
    private static List<String> parseList(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return list;
        }
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                list.add(t);
            }
        }
        return list;
    }

    /** https:// -> http://，非 https 原样返回 */
    private static String toHttp(String url) {
        return url != null && url.startsWith("https://")
                ? "http://" + url.substring("https://".length()) : url;
    }

    /**
     * 将 raw.githubusercontent.com 的 URL 转为 jsDelivr CDN URL；
     * 非该域名返回 null。例：
     * raw:  https://raw.githubusercontent.com/{owner}/{repo}/{branch}/{path}
     * jsd:  https://cdn.jsdelivr.net/gh/{owner}/{repo}@{branch}/{path}
     */
    private static String toJsDelivr(String rawUrl) {
        String prefix = "https://raw.githubusercontent.com/";
        if (rawUrl == null || !rawUrl.startsWith(prefix)) {
            return null;
        }
        String rest = rawUrl.substring(prefix.length());
        int firstSlash = rest.indexOf('/');
        int secondSlash = rest.indexOf('/', firstSlash + 1);
        if (firstSlash <= 0 || secondSlash <= 0) {
            return null;
        }
        String ownerRepo = rest.substring(0, secondSlash);
        String branchPath = rest.substring(secondSlash + 1);
        return "https://cdn.jsdelivr.net/gh/" + ownerRepo + "@" + branchPath;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 启动后台刷新任务：立即异步刷新一次，之后按配置周期刷新。
     * 代理未启用或已启动时幂等。
     */
    public void start() {
        if (!enabled) {
            log.info("代理池未启用（crawler.proxy.enabled=false），爬取将使用直连");
            return;
        }
        if (scheduler != null && !scheduler.isShutdown()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "proxy-pool-refresh");
            t.setDaemon(true);
            return t;
        });
        workerPool = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "proxy-worker");
            t.setDaemon(true);
            return t;
        });
        scheduler.execute(this::refresh);
        scheduler.scheduleWithFixedDelay(this::refresh, refreshMinutes, refreshMinutes, TimeUnit.MINUTES);
        log.info("代理池已启动：{} 个数据源、{} 个镜像前缀，刷新间隔 {} 分钟，验证目标 {}（备选 {}）",
                sources.size(), mirrorPrefixes.size(), refreshMinutes, validateUrl, validateUrlBackup);
        addEvent("代理池已启动：%d 个数据源、%d 个镜像前缀，刷新间隔 %d 分钟，验证目标 %s（备选 %s）",
                sources.size(), mirrorPrefixes.size(), refreshMinutes, validateUrl, validateUrlBackup);
        addEvent("已提交首轮刷新任务");
    }

    /** 拉取并验证一轮代理，更新可用池。 */
    private void refresh() {
        try {
            addEvent("开始刷新：拉取 %d 个数据源…", sources.size());
            Set<String> rawSet = fetchAllCandidates();
            lastCandidateCount = rawSet.size();
            if (rawSet.isEmpty()) {
                log.warn("本轮所有数据源（含镜像）均拉取失败，保留现有代理池（{} 个）。"
                        + "可检查网络，或更换 crawler.proxy.sources / crawler.proxy.mirror.prefixes", available.size());
                addEvent("本轮所有数据源均拉取失败，保留现有代理池（%d 个）", available.size());
                lastRefresh = LocalDateTime.now();
                return;
            }
            addEvent("拉取完成：候选代理共 %d 条", rawSet.size());

            List<Proxy> candidates = new ArrayList<>(rawSet.size());
            for (String s : rawSet) {
                String[] hp = s.split(":");
                candidates.add(new Proxy(Proxy.Type.HTTP,
                        InetSocketAddress.createUnresolved(hp[0], Integer.parseInt(hp[1]))));
            }

            // 候选通常数千条，全部验证需数十分钟；随机打乱后按配置条数采样验证。
            Collections.shuffle(candidates);
            int sampleCount = validateSampleCount;
            int validateLimit = Math.min(candidates.size(), sampleCount);
            if (candidates.size() > validateLimit) {
                log.info("候选代理 {} 条，按配置采样验证其中 {} 条", candidates.size(), validateLimit);
                addEvent("候选 %d 条，采样验证其中 %d 条（并发 %d，超时 %d 秒）",
                        candidates.size(), validateLimit, parallelism, validateTimeoutSeconds);
            }
            List<Proxy> toValidate = new ArrayList<>(candidates.subList(0, validateLimit));

            // 并发验证连通性
            CopyOnWriteArrayList<Proxy> valid = new CopyOnWriteArrayList<>();
            validatedDone.set(0);
            validateTotal = toValidate.size();
            int progressStep = Math.max(1, toValidate.size() / 10);
            // 每条任务的硬上限：主目标超时 + 备选目标超时，再加 1 秒缓冲。
            // JDK HttpClient 在某些死代理下 request.timeout() 无法强制中断 client.send()，
            // 必须用 Future.get(超时) 兜底并 cancel(true)，否则单条会卡死导致 done 永远到不了 total。
            long perTaskTimeoutMs = (long) validateTimeoutSeconds * 2 * 1000L + 1000L;
            List<Future<Boolean>> futures = new ArrayList<>(toValidate.size());
            for (Proxy p : toValidate) {
                futures.add(workerPool.submit(() -> validate(p)));
            }
            for (int i = 0; i < toValidate.size(); i++) {
                Proxy p = toValidate.get(i);
                Future<Boolean> f = futures.get(i);
                try {
                    if (Boolean.TRUE.equals(f.get(perTaskTimeoutMs, TimeUnit.MILLISECONDS))) {
                        valid.add(p);
                    }
                } catch (TimeoutException te) {
                    f.cancel(true);
                    log.debug("代理 {} 验证未在 {}ms 内返回，已强制取消", p, perTaskTimeoutMs);
                    addEvent("代理 %s 验证超时（>%dms）", p, perTaskTimeoutMs);
                } catch (ExecutionException | InterruptedException ee) {
                    if (ee instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    log.debug("代理 {} 验证异常: {}", p, ee.getMessage());
                }
                int done = validatedDone.incrementAndGet();
                if (done % progressStep == 0 || done == toValidate.size()) {
                    addEvent("验证进度：%d / %d", done, toValidate.size());
                }
            }

            // 随机打乱后截取池上限，避免固定顺序被识别
            Collections.shuffle(valid);
            List<Proxy> kept = valid.size() > maxCount
                    ? new ArrayList<>(valid.subList(0, maxCount)) : new ArrayList<>(valid);

            available.clear();
            available.addAll(kept);
            // 清理已不在池中的代理缓存连接
            proxyClients.keySet().removeIf(p -> !available.contains(p));

            lastRefresh = LocalDateTime.now();
            log.info("代理池刷新完成：验证 {} 条候选，可用 {} 个代理", toValidate.size(), kept.size());
            addEvent("刷新完成：验证 %d 条候选，可用 %d 个代理", toValidate.size(), kept.size());
            if (kept.isEmpty()) {
                addEvent("提示：本批验证均失败，可等待下轮自动刷新，或调整 validate.timeout.seconds / sample.factor / sources");
            }
        } catch (Exception e) {
            log.warn("代理池刷新异常: {}", e.getMessage());
            addEvent("刷新异常：%s", e.getMessage());
        } finally {
            refreshPending.set(false);
        }
    }

    /**
     * 并行拉取所有数据源的候选 URL（原始 + 各镜像前缀 + jsDelivr），解析去重返回 ip:port 集合。
     * 直连 GitHub raw 命中率低且慢，给予更短超时，避免拖慢整体。
     */
    private Set<String> fetchAllCandidates() {
        List<String> urls = new ArrayList<>();
        for (String source : sources) {
            List<String> candidates = new ArrayList<>();
            candidates.add(source);
            for (String prefix : mirrorPrefixes) {
                if (!prefix.isEmpty()) {
                    candidates.add(prefix + source);
                }
            }
            String jsd = toJsDelivr(source);
            if (jsd != null) {
                candidates.add(jsd);
            }
            urls.addAll(new LinkedHashSet<>(candidates));
        }

        Set<String> result = Collections.synchronizedSet(new LinkedHashSet<>());
        List<Callable<Void>> tasks = new ArrayList<>(urls.size());
        for (String url : urls) {
            tasks.add(() -> {
                try {
                    Duration timeout = url.startsWith("https://raw.githubusercontent.com/")
                            ? Duration.ofSeconds(5) : Duration.ofSeconds(fetchTimeoutSeconds);
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(timeout)
                            .header("User-Agent", FETCH_UA)
                            .GET()
                            .build();
                    HttpResponse<String> resp = directClient.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200) {
                        int added = 0;
                        for (String line : resp.body().split("\\R")) {
                            String t = line.trim();
                            if (IP_PORT.matcher(t).matches() && result.add(t)) {
                                added++;
                            }
                        }
                        log.info("代理源 {} 拉取成功，新增 {} 条候选", url, added);
                        if (added > 0) {
                            addEvent("代理源拉取成功：%s（新增 %d 条）", url, added);
                        }
                    } else {
                        log.debug("代理源 {} 返回状态码 {}", url, resp.statusCode());
                    }
                } catch (Exception e) {
                    log.debug("拉取代理源 {} 失败: {}", url, e.getMessage());
                    addEvent("代理源拉取失败：%s（%s）", url, e.getMessage());
                }
                return null;
            });
        }
        try {
            workerPool.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result;
    }

    /**
     * 验证代理连通性：通过代理依次请求主验证目标（HTTPS）与备选目标（HTTP），
     * 任一返回 2xx/3xx 即视为可用。兼顾仅支持 HTTP 的纯代理。
     */
    /**
     * 验证代理连通性。
     * 仅校验主目标（HTTPS），失败即丢弃：爬取 douyin 等 HTTPS 站点需要代理支持 CONNECT 隧道，
     * 纯 HTTP 代理对 https 会返回 "Tunnel failed, got: 400"，混进可用池只会浪费一轮重试。
     */
    private boolean validate(Proxy proxy) {
        return checkTarget(proxy, validateUrl);
    }

    private boolean checkTarget(Proxy proxy, String target) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .proxy(ProxySelector.of((InetSocketAddress) proxy.address()))
                    .connectTimeout(Duration.ofSeconds(validateTimeoutSeconds))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(target))
                    .timeout(Duration.ofSeconds(validateTimeoutSeconds))
                    .header("User-Agent", FETCH_UA)
                    .GET()
                    .build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() >= 200 && resp.statusCode() < 400;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 随机返回一个可用代理；池为空返回 null（调用方应直连兜底）。
     * <p>
     * 采用「池内随机 + 排除指定」策略：
     * <ul>
     *   <li>正常请求传 {@code exclude=null}：池内完全随机，可用代理被随机反复命中 —— 满足「能用的 IP 重复使用」；</li>
     *   <li>失败重试传 {@code exclude=上次代理}：随机取一个与上次不同的代理 —— 满足「多换 IP 多试几次」。</li>
     * </ul>
     */
    public Proxy next(Proxy exclude) {
        List<Proxy> list = available;
        int size = list.size();
        if (size == 0) {
            return null;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (size == 1) {
            return list.get(0);
        }
        if (exclude == null) {
            return list.get(rnd.nextInt(size));
        }
        // 排除指定代理：随机重试，最多尝试 N 次，避免无限循环
        for (int i = 0; i < 10; i++) {
            Proxy p = list.get(rnd.nextInt(size));
            if (!p.equals(exclude)) {
                return p;
            }
        }
        // 兜底：全池顺序找一个非 exclude 的
        for (Proxy p : list) {
            if (!p.equals(exclude)) {
                return p;
            }
        }
        return list.get(rnd.nextInt(size));
    }

    /** 兼容无参调用：池内随机（好代理会被重复使用）。 */
    public Proxy next() {
        return next(null);
    }

    /**
     * 将失败代理移出可用池并释放其缓存连接（下次刷新重新验证拉回）。
     * <p>
     * 仅在收到明确反爬信号（503/403/429）时由调用方调用。普通超时/连接抖动
     * 不剔除，避免把偶发抖动的可用代理永久淘汰，从而支持「可用 IP 重复使用」。
     */
    public void markFailed(Proxy proxy) {
        if (proxy == null) {
            return;
        }
        if (available.remove(proxy)) {
            proxyClients.remove(proxy);
            log.debug("代理 {} 使用失败，已移出可用池", proxy);
            addEvent("代理 %s 使用失败，已移出可用池（剩余 %d）", proxy, available.size());
        }
    }

    /** 获取指定代理对应的 HttpClient（按代理缓存复用连接）。 */
    public HttpClient clientFor(Proxy proxy) {
        return proxyClients.computeIfAbsent(proxy, k -> HttpClient.newBuilder()
                .proxy(ProxySelector.of((InetSocketAddress) k.address()))
                .connectTimeout(Duration.ofSeconds(requestTimeoutSeconds))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build());
    }

    /** 直连 HttpClient（代理池为空时的兜底）。 */
    public HttpClient directClient() {
        return directClient;
    }

    /** 代理池状态快照，供 UI 展示。 */
    public ProxyStatus getStatus() {
        return new ProxyStatus(enabled, available.size(), lastCandidateCount, lastRefresh);
    }

    /** 连通性验证进度快照，供 UI 展示；total=0 表示当前未在验证。 */
    public record ValidationProgress(int done, int total) {
        public boolean inProgress() {
            return total > 0;
        }
    }

    /** 当前连通性验证进度（done/total）；未在验证时 total=0。 */
    public ValidationProgress getValidationProgress() {
        return new ValidationProgress(validatedDone.get(), validateTotal);
    }

    /** 最近处理过程事件（时间正序，最多 EVENT_LOG_LIMIT 条），供详情窗口展示。 */
    public List<String> getRecentEvents() {
        return new ArrayList<>(eventLog);
    }

    /** 清空事件日志（供详情窗口「清空事件」按钮）。 */
    public void clearEvents() {
        eventLog.clear();
    }

    /** 当前可用代理快照列表，供详情窗口展示。 */
    public List<Proxy> getAvailableProxies() {
        return new ArrayList<>(available);
    }

    /**
     * 手动触发一轮刷新（供详情窗口「立即刷新」按钮）。
     * 已在刷新/排队中、未启用或已关闭时返回 false。
     */
    public boolean refreshNow() {
        if (!enabled) {
            addEvent("手动刷新被忽略：代理池未启用");
            return false;
        }
        if (scheduler == null || scheduler.isShutdown()) {
            addEvent("手动刷新被忽略：代理池已关闭");
            return false;
        }
        if (!refreshPending.compareAndSet(false, true)) {
            addEvent("手动刷新被忽略：上一轮刷新仍在进行");
            return false;
        }
        addEvent("手动触发刷新");
        scheduler.execute(this::refresh);
        return true;
    }

    /** 追加一条处理过程事件（环形缓冲，超出上限丢弃最旧）。 */
    private void addEvent(String fmt, Object... args) {
        String line = "[" + LocalTime.now().format(EVENT_TIME_FMT) + "] " + String.format(fmt, args);
        eventLog.add(line);
        if (eventLog.size() > EVENT_LOG_LIMIT) {
            eventLog.remove(0);
        }
    }

    /** 释放所有后台线程与缓存连接。 */
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (workerPool != null) {
            workerPool.shutdownNow();
        }
        available.clear();
        proxyClients.clear();
        usageCount.clear();
        log.info("代理池已关闭");
        addEvent("代理池已关闭");
    }
}
