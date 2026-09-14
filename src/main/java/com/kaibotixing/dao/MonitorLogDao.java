package com.kaibotixing.dao;

import com.kaibotixing.model.MonitorLog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 业务日志 DAO。
 */
public class MonitorLogDao {

    public void insert(MonitorLog log) throws SQLException {
        String sql = "INSERT INTO monitor_log (level, source, message) VALUES (?, ?, ?)";
        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, log.getLevel());
            ps.setString(2, log.getSource());
            ps.setString(3, log.getMessage());
            ps.executeUpdate();
        }
    }

    public List<MonitorLog> findRecent(int limit) throws SQLException {
        String sql = "SELECT id, level, source, message, create_time FROM monitor_log " +
                "ORDER BY create_time DESC LIMIT ?";
        List<MonitorLog> list = new ArrayList<>();
        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MonitorLog log = new MonitorLog();
                    log.setId(rs.getLong("id"));
                    log.setLevel(rs.getString("level"));
                    log.setSource(rs.getString("source"));
                    log.setMessage(rs.getString("message"));
                    Timestamp t = rs.getTimestamp("create_time");
                    if (t != null) {
                        log.setCreateTime(t.toLocalDateTime());
                    }
                    list.add(log);
                }
            }
        }
        return list;
    }

    public void clear() throws SQLException {
        String sql = "DELETE FROM monitor_log";
        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
