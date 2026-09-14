package com.kaibotixing.model;

import java.time.LocalDateTime;

/**
 * 业务日志实体。
 */
public class MonitorLog {
    private Long id;
    private String level;
    private String source;
    private String message;
    private LocalDateTime createTime;

    public MonitorLog() {
    }

    public MonitorLog(String level, String source, String message) {
        this.level = level;
        this.source = source;
        this.message = message;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
