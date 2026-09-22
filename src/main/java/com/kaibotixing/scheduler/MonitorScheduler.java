package com.kaibotixing.scheduler;

import com.kaibotixing.crawler.CrawlResult;
import com.kaibotixing.crawler.DouyinCrawler;
import com.kaibotixing.dao.MonitorConfigDao;
import com.kaibotixing.dao.AnchorDao;
import com.kaibotixing.dao.AnchorLiveSessionDao;
import com.kaibotixing.model.Anchor;
import com.kaibotixing.model.LiveStatus;
import com.kaibotixing.service.AlertService;
import com.kaibotixing.service.LogService;
import com.kaibotixing.util.ConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 监控调度器：定时（默认每 30 秒）读取启用中的主播，使用线程池并发爬取，
 * 检测开播边沿并触发提醒，结果持久化到数据库。
 */
public class MonitorScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonitorScheduler.class);

    private final ScheduledExecutorService scheduler;
    private final ExecutorService crawlPool;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final DouyinCrawler crawler;
    private final AnchorDao anchorDao = new AnchorDao();
    private final AnchorLiveSessionDao sessionDao = new AnchorLiveSessionDao();
    private final MonitorConfigDao configDao = new MonitorConfigDao();
    private final LogService logService;
    private final AlertService alertService;

    /** 弹窗提醒开关（默认开启） */
    private volatile boolean alertEnabled = true;

    /** 记录每个主播上一次状态，用于边沿检测 */
    private final Map<Long, LiveStatus> lastStatusMap = new ConcurrentHashMap<>();

    /** 当前监控状态监听器（供 UI 刷新） */
    private final List<StatusListener> statusListeners = new ArrayList<>();

    private ScheduledFuture<?> scheduledFuture;

    public interface StatusListener {
        void onStatusUpdated(List<Anchor> anchors);
    }

    public MonitorScheduler(DouyinCrawler crawler, LogService logService, AlertService alertService) {
        this.crawler = crawler;
        this.logService = logService;
        this.alertService = alertService;

        int threadCount = ConfigUtil.getInt("monitor.thread.pool.size", 5);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "monitor-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.crawlPool = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r, "crawler-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void addStatusListener(StatusListener listener) {
        statusListeners.add(listener);
    }

    public synchronized void removeStatusListener(StatusListener listener) {
        statusListeners.remove(listener);
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * 设置弹窗提醒开关（关闭后开播时不再弹窗与播放提示音）。
     */
    public void setAlertEnabled(boolean enabled) {
        this.alertEnabled = enabled;
    }

    /**
     * 启动监控：读取配置间隔并开始定时调度。
     */
    public synchronized void start() {
        if (running.get()) {
            return;
        }
        running.set(true);

        int interval = ConfigUtil.getInt("monitor.interval.seconds", 30);
        try {
            String dbInterval = configDao.get("monitor.interval.seconds", null);
            if (dbInterval != null) {
                interval = Integer.parseInt(dbInterval.trim());
            }
        } catch (Exception e) {
            log.warn("读取数据库监控间隔失败，使用默认值 {} 秒", interval);
        }

        // 初始化 lastStatusMap，避免启动时误判为开播
        initLastStatus();

        scheduledFuture = scheduler.scheduleWithFixedDelay(
                this::runOneRound, 0, interval, TimeUnit.SECONDS);

        logService.info("监控", "监控已启动，爬取间隔 " + interval + " 秒");
    }

    /**
     * 停止监控调度。
     */
    public synchronized void stop() {
        if (!running.get()) {
            return;
        }
        running.set(false);
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
        logService.info("监控", "监控已停止");
    }

    private void initLastStatus() {
        try {
            List<Anchor> anchors = anchorDao.findEnabled();
            for (Anchor a : anchors) {
                if (a.getLastStatus() != null) {
                    lastStatusMap.put(a.getId(), a.getLastStatus());
                }
            }
        } catch (SQLException e) {
            log.warn("初始化状态失败: {}", e.getMessage());
        }
    }

    /**
     * 执行一轮爬取。
     */
    private void runOneRound() {
        if (!running.get()) {
            return;
        }
        List<Anchor> anchors;
        try {
            anchors = anchorDao.findEnabled();
        } catch (SQLException e) {
            logService.error("监控", "读取主播列表失败: " + e.getMessage());
            return;
        }

        if (anchors.isEmpty()) {
            return;
        }

        logService.info("监控", "开始一轮爬取，主播数: " + anchors.size());

        // 并发爬取：每个主播提交到线程池并持有 Future。
        // 每个任务独立设置硬超时：JDK HttpClient 在死代理上 request.timeout() 可能失效，
        // 导致 crawl() 永久阻塞；用 Future.get(单任务超时) 兜底并 cancel(true)，超时后
        // 跳过该主播继续下一轮，保证「上一轮永远不完成」的情况被强制终结（否则整轮被跳过）。
        long taskTimeoutMs = computeTaskTimeoutMs();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>(anchors.size());
        for (Anchor a : anchors) {
            try {
                futures.add(crawlPool.submit(() -> crawlOne(a)));
            } catch (java.util.concurrent.RejectedExecutionException e) {
                log.warn("爬取任务提交被拒绝（线程池可能已关闭）: {}", e.getMessage());
                break;
            }
        }

        // 有界等待：对每个任务单独 get(超时)。超时的任务强制取消，不阻塞本轮收尾，
        // 从而保证 inFlightTasks 能归零、下一轮按间隔继续触发（不再「每轮跳过」）。
        for (java.util.concurrent.Future<?> f : futures) {
            try {
                f.get(taskTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                f.cancel(true);
                log.warn("监控", "某主播爬取超时（> " + taskTimeoutMs + "ms），已强制取消，继续下一轮");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("监控", "爬取等待被中断");
                break;
            } catch (java.util.concurrent.ExecutionException ee) {
                log.warn("监控", "爬取任务异常: " + ee.getCause());
            }
        }

        // 汇总刷新列表
        List<Anchor> updated = new ArrayList<>();
        try {
            updated.addAll(anchorDao.findEnabled());
        } catch (SQLException e) {
            logService.error("监控", "刷新主播状态列表失败: " + e.getMessage());
        }

        notifyListeners(updated);
    }

    /**
     * 单个主播爬取任务的硬超时上限：
     * 一次 crawl 最坏 = 直播间页 fetch(重试×超时) + 主页 fetch(重试×超时) ≈ 2 × retry × timeout 秒。
     * 取该值的 1.5 倍并留缓冲，避免误杀正常任务，又能终结卡死任务。
     */
    private long computeTaskTimeoutMs() {
        int attempts = Math.min(50, Math.max(2, ConfigUtil.getInt("crawler.retry.count", 15)));
        int timeout = Math.max(3, ConfigUtil.getInt("crawler.timeout.seconds", 10));
        // 2(直播间+主页) × attempts × timeout 秒，再乘 1.5 缓冲
        return (long) (2 * attempts * timeout * 1.5) * 1000L;
    }

    private void crawlOne(Anchor anchor) {
        LiveStatus previous = lastStatusMap.getOrDefault(anchor.getId(), anchor.getLastStatus());

        CrawlResult result;
        try {
            result = crawler.crawl(anchor);
        } catch (Exception e) {
            result = CrawlResult.error("爬取异常: " + e.getMessage());
        }

        LiveStatus current = result.status();
        LocalDateTime now = LocalDateTime.now();

        // 自动从主页反查到 web_rid 时写回数据库（仅在当前 anchor 未配置或不一致时），
        // 下次监控将直接走直播间页面，避开主页反爬导致的状态未知。
        if (result.discoveredWebRid() != null
                && !result.discoveredWebRid().equals(anchor.getWebRid())) {
            try {
                anchorDao.updateWebRid(anchor.getId(), result.discoveredWebRid());
                anchor.setWebRid(result.discoveredWebRid());
                logService.info("监控", "主播「" + anchor.getNickname() + "」自动补充房间号: "
                        + result.discoveredWebRid());
            } catch (SQLException e) {
                log.warn("自动补充房间号失败: {}", e.getMessage());
            }
        }

        // 状态未知时：保留原状态，但仍记录本次检测时间，不做边沿检测、不触发提醒
        if (current == LiveStatus.UNKNOWN) {
            try {
                anchorDao.updateLastCheckTime(anchor.getId());
            } catch (SQLException e) {
                log.warn("更新最近检测时间失败: {}", e.getMessage());
            }
            logService.warn("监控", "主播「" + anchor.getNickname() + "」状态未知，保留原状态（"
                    + (previous == null ? "未知" : previous.getLabel()) + "）"
                    + (result.errorMsg() != null ? ": " + result.errorMsg() : ""));
            return;
        }

        // 更新主播最新状态
        try {
            anchorDao.updateStatus(anchor.getId(), current, result.roomId());
        } catch (SQLException e) {
            log.warn("更新主播状态失败: {}", e.getMessage());
        }

        // 开播边沿检测：记录开播会话 + 弹窗提醒
        if (current == LiveStatus.LIVE && previous != LiveStatus.LIVE) {
            logService.info("监控", "检测到主播「" + anchor.getNickname() + "」开播，房间号: "
                    + (result.roomId() == null ? "未知" : result.roomId()));
            try {
                sessionDao.insertSession(anchor.getId(), anchor.getNickname(), now);
            } catch (SQLException e) {
                log.warn("记录开播会话失败: {}", e.getMessage());
            }
            if (alertEnabled) {
                alertService.alert(anchor.getNickname(), result.roomId());
            }
        } else if (current != LiveStatus.LIVE && previous == LiveStatus.LIVE) {
            // 关播边沿检测：回填关播时间与开播时长
            logService.info("监控", "检测到主播「" + anchor.getNickname() + "」已关播");
            try {
                sessionDao.closeSession(anchor.getId(), now);
            } catch (SQLException e) {
                log.warn("回填关播会话失败: {}", e.getMessage());
            }
        } else if (current != LiveStatus.LIVE) {
            logService.info("监控", "主播「" + anchor.getNickname() + "」状态: "
                    + current.getLabel() + (result.errorMsg() != null ? " (" + result.errorMsg() + ")" : ""));
        }

        lastStatusMap.put(anchor.getId(), current);
    }

    private synchronized void notifyListeners(List<Anchor> anchors) {
        for (StatusListener listener : statusListeners) {
            try {
                listener.onStatusUpdated(anchors);
            } catch (Exception e) {
                log.warn("状态监听器异常: {}", e.getMessage());
            }
        }
    }

    public void shutdown() {
        stop();
        scheduler.shutdownNow();
        crawlPool.shutdownNow();
    }
}
