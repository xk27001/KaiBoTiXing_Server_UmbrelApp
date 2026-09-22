package com.kaibotixing.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * 监控配置 DAO。
 */
public class MonitorConfigDao {

    public Map<String, String> findAll() throws SQLException {
        String sql = "SELECT cfg_key, cfg_value FROM monitor_config";
        Map<String, String> map = new HashMap<>();
        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                map.put(rs.getString("cfg_key"), rs.getString("cfg_value"));
            }
        }
        return map;
    }

    public String get(String key, String defaultValue) throws SQLException {
        String sql = "SELECT cfg_value FROM monitor_config WHERE cfg_key=?";
        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("cfg_value");
                }
            }
        }
        return defaultValue;
    }

    /**
     * 仅在配置不存在时写入默认值，不覆盖用户在网页中保存的设置。
     */
    public void setIfAbsent(String key, String value) throws SQLException {
        String sql = "INSERT IGNORE INTO monitor_config (cfg_key, cfg_value) VALUES (?, ?)";
        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }
    public void set(String key, String value) throws SQLException {
        String sql = "INSERT INTO monitor_config (cfg_key, cfg_value) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE cfg_value = VALUES(cfg_value)";
        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }
}
