package com.kaibotixing.server;

import com.kaibotixing.crawler.DouyinWebCrawler;
import com.kaibotixing.crawler.ProxyPoolService;
import com.kaibotixing.dao.AnchorLiveSessionDao;
import com.kaibotixing.dao.DataSourceManager;
import com.kaibotixing.dao.MonitorConfigDao;
import com.kaibotixing.model.Anchor;
import com.kaibotixing.model.AnchorLiveSession;
import com.kaibotixing.model.MonitorLog;
import com.kaibotixing.scheduler.MonitorScheduler;
import com.kaibotixing.service.AlertService;
import com.kaibotixing.service.AnchorService;
import com.kaibotixing.service.LogService;
import com.kaibotixing.util.ConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Umbrel/容器模式下的无界面服务运行时：复用桌面版的 DAO、爬取器和调度器。
 */
public final class ServerRuntime implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ServerRuntime.class);

    private final AnchorService anchorService = new AnchorService();
    private final LogService logService = new LogService();
    private final AlertService alertService = new AlertService();
    private final MonitorConfigDao configDao = new MonitorConfigDao();
    private final AnchorLiveSessionDao sessionDao = new AnchorLiveSessionDao();
    private final ProxyPoolService proxyPool = new ProxyPoolService();
    private final MonitorScheduler scheduler;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile boolean alertEnabled = true;
    private volatile boolean logEnabled = true;
    private volatile int monitorIntervalSeconds = 30;
    private volatile int proxyValidateSampleCount = 250;

    public ServerRuntime() throws SQLException {
        scheduler = new MonitorScheduler(new DouyinWebCrawler(proxyPool), logService, alertService);
        loadSettings();
    }

    /**
     * 启动后台代理池和监控调度器。数据库会在首次访问 DAO 时完成初始化。
     */
    public synchronized void start() {
        proxyPool.start();
        if (ConfigUtil.getBoolean("server.monitor.auto-start", true)) {
            scheduler.start();
        } else {
            log.info("服务器监控自动启动已禁用，请在 Web 控制台手动启动");
        }
        log.info("KaiBoTiXing 服务端已启动，监听端口 {}", ConfigUtil.getInt("server.port", 8080));
    }

    private void loadSettings() throws SQLException {
        Map<String, String> config = configDao.findAll();
        monitorIntervalSeconds = parsePositiveInt(
                config.get("monitor.interval.seconds"), 30, 5, 86_400);
        alertEnabled = enabled(config.get("alert.enabled"), true);
        logEnabled = enabled(config.get("log.enabled"), true);
        proxyValidateSampleCount = parsePositiveInt(
                config.get("crawler.proxy.validate.sample.count"),
                proxyPool.getValidateSampleCount(), 1, 10_000);
        scheduler.setAlertEnabled(alertEnabled);
        logService.setLogEnabled(logEnabled);
        proxyPool.setValidateSampleCount(proxyValidateSampleCount);
    }

    public synchronized Map<String, Object> settingsSnapshot() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("monitorIntervalSeconds", monitorIntervalSeconds);
        settings.put("alertEnabled", alertEnabled);
        settings.put("logEnabled", logEnabled);
        settings.put("proxyValidateSampleCount", proxyValidateSampleCount);
        return settings;
    }

    /**
     * 保存 Web 控制台设置；若监控正在运行，则以新间隔重启调度器。
     */
    public synchronized void saveSettings(int intervalSeconds, boolean alert, boolean logToDatabase,
                                          int sampleCount) throws SQLException {
        int safeInterval = Math.max(5, Math.min(intervalSeconds, 86_400));
        int safeSampleCount = Math.max(1, Math.min(sampleCount, 10_000));
        configDao.set("monitor.interval.seconds", Integer.toString(safeInterval));
        configDao.set("alert.enabled", alert ? "1" : "0");
        configDao.set("log.enabled", logToDatabase ? "1" : "0");
        configDao.set("crawler.proxy.validate.sample.count", Integer.toString(safeSampleCount));

        monitorIntervalSeconds = safeInterval;
        alertEnabled = alert;
        logEnabled = logToDatabase;
        proxyValidateSampleCount = safeSampleCount;
        scheduler.setAlertEnabled(alert);
        logService.setLogEnabled(logToDatabase);
        proxyPool.setValidateSampleCount(safeSampleCount);

        if (scheduler.isRunning()) {
            scheduler.stop();
            scheduler.start();
        }
        logService.info("控制", "Web 控制台已更新监控设置");
    }

    public List<Anchor> anchors() throws SQLException {
        return anchorService.findAll();
    }

    public long addAnchor(Anchor anchor) throws SQLException {
        long id = anchorService.add(anchor);
        logService.info("控制", "新增主播「" + anchor.getNickname() + "」");
        return id;
    }

    public void updateAnchor(Anchor anchor) throws SQLException {
        anchorService.update(anchor);
        logService.info("控制", "更新主播「" + anchor.getNickname() + "」");
    }

    public void deleteAnchor(long id) throws SQLException {
        anchorService.delete(id);
        logService.info("控制", "删除主播 ID " + id);
    }

    public List<MonitorLog> logs(int limit) {
        return logService.recent(clampLimit(limit));
    }

    public void clearLogs() {
        logService.clear();
    }

    public List<AnchorLiveSession> sessions(int limit) throws SQLException {
        return sessionDao.findRecent(clampLimit(limit));
    }

    public void startMonitor() {
        scheduler.start();
        logService.info("控制", "Web 控制台已启动监控");
    }

    public void stopMonitor() {
        scheduler.stop();
        logService.info("控制", "Web 控制台已停止监控");
    }

    public boolean isMonitorRunning() {
        return scheduler.isRunning();
    }

    public Map<String, Object> proxySnapshot() {
        ProxyPoolService.ProxyStatus status = proxyPool.getStatus();
        ProxyPoolService.ValidationProgress progress = proxyPool.getValidationProgress();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", status.enabled());
        result.put("available", status.available());
        result.put("candidateCount", status.candidateCount());
        result.put("lastRefresh", status.lastRefresh());
        result.put("validationDone", progress.done());
        result.put("validationTotal", progress.total());
        result.put("events", proxyPool.getRecentEvents());
        result.put("proxies", proxyList());
        return result;
    }

    public boolean refreshProxyPool() {
        return proxyPool.refreshNow();
    }

    private List<Map<String, Object>> proxyList() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Proxy proxy : proxyPool.getAvailableProxies()) {
            InetSocketAddress address = (InetSocketAddress) proxy.address();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", proxy.type().name());
            item.put("host", address.getHostString());
            item.put("port", address.getPort());
            list.add(item);
        }
        return list;
    }

    private static int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, 500));
    }

    private static int parsePositiveInt(String value, int fallback, int min, int max) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(Integer.parseInt(value.trim()), max));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean enabled(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return !"0".equals(value.trim());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdown();
        proxyPool.shutdown();
        DataSourceManager.close();
        log.info("KaiBoTiXing 服务端已停止");
    }
}
