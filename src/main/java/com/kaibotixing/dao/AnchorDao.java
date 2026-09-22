package com.kaibotixing.dao;

import com.kaibotixing.model.Anchor;
import com.kaibotixing.model.LiveStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 主播 DAO。
 */
public class AnchorDao {

    public List<Anchor> findAll() throws SQLException {
        String sql = "SELECT id, nickname, douyin_id, web_rid, home_url, remark, enabled, " +
                "last_status, last_check_time, create_time, update_time FROM anchor ORDER BY id";
        List<Anchor> list = new ArrayList<>();
        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(map(rs));
            }
        }
        return list;
    }

    public List<Anchor> findEnabled() throws SQLException {
        String sql = "SELECT id, nickname, douyin_id, web_rid, home_url, remark, enabled, " +
                "last_status, last_check_time, create_time, update_time FROM anchor WHERE enabled = 1 ORDER BY id";
        List<Anchor> list = new ArrayList<>();
        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(map(rs));
            }
        }
        return list;
    }

    public long insert(Anchor a) throws SQLException {
        String sql = "INSERT INTO anchor (nickname, douyin_id, web_rid, home_url, remark, enabled, last_status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, a.getNickname());
            ps.setString(2, a.getDouyinId());
            ps.setString(3, a.getWebRid());
            ps.setString(4, a.getHomeUrl());
            ps.setString(5, a.getRemark());
            ps.setBoolean(6, a.isEnabled());
            ps.setString(7, a.getLastStatus().name());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        return -1;
    }

    public void update(Anchor a) throws SQLException {
        String sql = "UPDATE anchor SET nickname=?, douyin_id=?, web_rid=?, home_url=?, " +
                "remark=?, enabled=? WHERE id=?";
        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, a.getNickname());
            ps.setString(2, a.getDouyinId());
            ps.setString(3, a.getWebRid());
            ps.setString(4, a.getHomeUrl());
            ps.setString(5, a.getRemark());
            ps.setBoolean(6, a.isEnabled());
            ps.setLong(7, a.getId());
            ps.executeUpdate();
        }
    }

    /**
     * 仅更新主播的固定直播间房间号（web_rid），不影响其他字段。
     * 由爬取器从主页反查到 web_rid 后写回，避免下次再走主页被反爬。
     */
    public void updateWebRid(long id, String webRid) throws SQLException {
        String sql = "UPDATE anchor SET web_rid=?, update_time=NOW() WHERE id=?";
        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, webRid);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void delete(long id) throws SQLException {
        String sql = "DELETE FROM anchor WHERE id=?";
        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /**
     * 更新主播的最新开播状态与检测时间。
     * 注意：这里只更新 last_status 和 last_check_time，绝不更新 web_rid。
     * web_rid 是用户配置的固定直播间房间号，爬取得到的 roomId 是动态的，
     * 若覆盖会导致后续爬取请求错误的房间号。
     */
    public void updateStatus(long id, LiveStatus status, String roomId) throws SQLException {
        String sql = "UPDATE anchor SET last_status=?, last_check_time=NOW() WHERE id=?";
        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /**
     * 只更新最近检测时间，不改变上次可信状态。用于本次检测结果为 UNKNOWN 的场景。
     */
    public void updateLastCheckTime(long id) throws SQLException {
        String sql = "UPDATE anchor SET last_check_time=NOW() WHERE id=?";
        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private Anchor map(ResultSet rs) throws SQLException {
        Anchor a = new Anchor();
        a.setId(rs.getLong("id"));
        a.setNickname(rs.getString("nickname"));
        a.setDouyinId(rs.getString("douyin_id"));
        a.setWebRid(rs.getString("web_rid"));
        a.setHomeUrl(rs.getString("home_url"));
        a.setRemark(rs.getString("remark"));
        a.setEnabled(rs.getBoolean("enabled"));
        a.setLastStatus(LiveStatus.from(rs.getString("last_status")));
        Timestamp lastCheck = rs.getTimestamp("last_check_time");
        if (lastCheck != null) {
            a.setLastCheckTime(lastCheck.toLocalDateTime());
        }
        a.setCreateTime(rs.getTimestamp("create_time").toLocalDateTime());
        a.setUpdateTime(rs.getTimestamp("update_time").toLocalDateTime());
        return a;
    }
}
