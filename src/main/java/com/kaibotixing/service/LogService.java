package com.kaibotixing.service;

import com.kaibotixing.dao.MonitorLogDao;
import com.kaibotixing.model.MonitorLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 日志服务：同时写数据库与 SLF4J 文件日志，并向订阅者推送（供 UI 实时刷新）。
 */
public class LogService {

    private static final Logger log = LoggerFactory.getLogger(LogService.class);

    private final MonitorLogDao logDao = new MonitorLogDao();
    private final List<LogListener> listeners = new ArrayList<>();

    /** 数据库日志记录开关（默认开启，关闭后仅写本地文件日志，不写数据库） */
    private volatile boolean logEnabled = true;

    public interface LogListener {
        void onLog(MonitorLog entry);
    }

    public synchronized void addListener(LogListener listener) {
        listeners.add(listener);
    }

    public synchronized void removeListener(LogListener listener) {
        listeners.remove(listener);
    }

    public void info(String source, String message) {
        write("INFO", source, message);
    }

    public void warn(String source, String message) {
        write("WARN", source, message);
    }

    public void error(String source, String message) {
        write("ERROR", source, message);
    }

    /**
     * 设置数据库日志记录开关（关闭后日志不再写入 monitor_log 表，本地文件日志不受影响）。
     */
    public void setLogEnabled(boolean enabled) {
        this.logEnabled = enabled;
    }

    public boolean isLogEnabled() {
        return logEnabled;
    }

    private void write(String level, String source, String message) {
        switch (level) {
            case "WARN" -> log.warn("[{}] {}", source, message);
            case "ERROR" -> log.error("[{}] {}", source, message);
            default -> log.info("[{}] {}", source, message);
        }

        MonitorLog entry = new MonitorLog(level, source, message);
        entry.setCreateTime(java.time.LocalDateTime.now());
        if (logEnabled) {
            try {
                logDao.insert(entry);
            } catch (SQLException e) {
                log.warn("写日志到数据库失败: {}", e.getMessage());
            }
        }

        notifyListeners(entry);
    }

    private synchronized void notifyListeners(MonitorLog entry) {
        for (LogListener listener : listeners) {
            try {
                listener.onLog(entry);
            } catch (Exception e) {
                log.warn("日志监听器异常: {}", e.getMessage());
            }
        }
    }

    public List<MonitorLog> recent(int limit) {
        try {
            return logDao.findRecent(limit);
        } catch (SQLException e) {
            log.warn("查询日志失败: {}", e.getMessage());
            return List.of();
        }
    }

    public void clear() {
        try {
            logDao.clear();
        } catch (SQLException e) {
            log.warn("清空日志失败: {}", e.getMessage());
        }
    }
}
