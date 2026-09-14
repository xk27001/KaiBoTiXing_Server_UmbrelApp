package com.kaibotixing.model;

import java.time.LocalDateTime;

/**
 * 主播实体。
 */
public class Anchor {
    private Long id;
    private String nickname;
    private String douyinId;
    private String webRid;
    private String homeUrl;
    private String remark;
    private boolean enabled = true;
    private LiveStatus lastStatus = LiveStatus.UNKNOWN;
    private LocalDateTime lastCheckTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getDouyinId() {
        return douyinId;
    }

    public void setDouyinId(String douyinId) {
        this.douyinId = douyinId;
    }

    public String getWebRid() {
        return webRid;
    }

    public void setWebRid(String webRid) {
        this.webRid = webRid;
    }

    public String getHomeUrl() {
        return homeUrl;
    }

    public void setHomeUrl(String homeUrl) {
        this.homeUrl = homeUrl;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public LiveStatus getLastStatus() {
        return lastStatus;
    }

    public void setLastStatus(LiveStatus lastStatus) {
        this.lastStatus = lastStatus;
    }

    public LocalDateTime getLastCheckTime() {
        return lastCheckTime;
    }

    public void setLastCheckTime(LocalDateTime lastCheckTime) {
        this.lastCheckTime = lastCheckTime;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
