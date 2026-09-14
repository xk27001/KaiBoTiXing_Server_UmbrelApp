package com.kaibotixing.dao;

import com.kaibotixing.model.AnchorLiveSession;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 主播开播会话 DAO：记录主播每次开播的开始时间、关播时间与开播时长。
 */
public class AnchorLiveSessionDao {

    /**
     * 开播时插入一条新会话记录（end_time 为 NULL，表示仍在直播中）。
     */
    public void insertSession(long anchorId, String nickname, LocalDateTime startTime) throws SQLException {
        String sql = "INSERT INTO anchor_live_session (anchor_id, nickname, start_time, end_time, duration_seconds) " +
                "VALUES (?, ?, ?, NULL, NULL)";
        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, anchorId);
            ps.setString(2, nickname);
            ps.setTimestamp(3, Timestamp.valueOf(startTime));
            ps.executeUpdate();
        }
    }

    /**
     * 关播时回填最近一条未关闭会话的关播时间与开播时长。
     * 只更新 end_time IS NULL 的最新一条，保证幂等。
     */
    public void closeSession(long anchorId, LocalDateTime endTime) throws SQLException {
        String sql = "UPDATE anchor_live_session SET end_time=?, " +
                "duration_seconds=TIMESTAMPDIFF(SECOND, start_time, ?) " +
                "WHERE anchor_id=? AND end_time IS NULL " +
                "ORDER BY id DESC LIMIT 1";
        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf(endTime));
            ps.setTimestamp(2, Timestamp.valueOf(endTime));
            ps.setLong(3, anchorId);
            ps.executeUpdate();
        }
    }

    /**
     * 查询某主播的所有开播会话（按开始时间倒序）。
     */
    public List<AnchorLiveSession> findByAnchor(long anchorId) throws SQLException {
        String sql = "SELECT id, anchor_id, nickname, start_time, end_time, duration_seconds " +
                "FROM anchor_live_session WHERE anchor_id=? ORDER BY start_time DESC";
        List<AnchorLiveSession> list = new ArrayList<>();
        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, anchorId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(map(rs));
                }
            }
        }
        return list;
    }

    /**
     * 查询所有主播的开播会话（按开始时间倒序，最多 limit 条），用于开播记录 Tab 展示。
     */
    public List<AnchorLiveSession> findRecent(int limit) throws SQLException {
        String sql = "SELECT id, anchor_id, nickname, start_time, end_time, duration_seconds " +
                "FROM anchor_live_session ORDER BY start_time DESC LIMIT ?";
        List<AnchorLiveSession> list = new ArrayList<>();
        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(map(rs));
                }
            }
        }
        return list;
    }

    private AnchorLiveSession map(ResultSet rs) throws SQLException {
        AnchorLiveSession s = new AnchorLiveSession();
        s.setId(rs.getLong("id"));
        s.setAnchorId(rs.getLong("anchor_id"));
        s.setNickname(rs.getString("nickname"));
        Timestamp start = rs.getTimestamp("start_time");
        if (start != null) {
            s.setStartTime(start.toLocalDateTime());
        }
        Timestamp end = rs.getTimestamp("end_time");
        if (end != null) {
            s.setEndTime(end.toLocalDateTime());
        }
        long duration = rs.getLong("duration_seconds");
        if (!rs.wasNull()) {
            s.setDurationSeconds(duration);
        }
        return s;
    }
}
