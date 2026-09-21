package com.kaibotixing.dao;

import com.kaibotixing.util.ConfigUtil;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;

/**
 * 数据源管理器：基于 HikariCP 连接池，启动时自动执行建表脚本。
 */
public final class DataSourceManager {

    private static final Logger log = LoggerFactory.getLogger(DataSourceManager.class);
    private static HikariDataSource dataSource;

    private DataSourceManager() {
    }

    public static synchronized DataSource getDataSource() {
        if (dataSource == null) {
            init();
        }
        return dataSource;
    }

    private static void init() {
        if (ConfigUtil.getBoolean("db.auto.create", true)) {
            ensureDatabaseExists();
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(buildJdbcUrl());
        config.setUsername(ConfigUtil.get("db.username"));
        config.setPassword(ConfigUtil.get("db.password"));
        config.setMaximumPoolSize(ConfigUtil.getInt("db.pool.size", 8));
        config.setConnectionTimeout(ConfigUtil.getInt("db.pool.timeout", 30000));
        config.setPoolName("KaiBoTiXingPool");
        config.setConnectionTestQuery("SELECT 1");
        dataSource = new HikariDataSource(config);
        log.info("数据库连接池初始化完成");

        initSchema();
    }

    private static String buildJdbcUrl() {
        String host = ConfigUtil.get("db.host");
        String port = ConfigUtil.get("db.port", "3306");
        String database = ConfigUtil.get("db.database");
        String params = ConfigUtil.get("db.params", "");
        return String.format("jdbc:mysql://%s:%s/%s?%s", host, port, database, params);
    }

    /**
     * 若目标数据库不存在则自动创建（连接不带库名的 URL）。
     */
    private static void ensureDatabaseExists() {
        String host = ConfigUtil.get("db.host");
        String port = ConfigUtil.get("db.port", "3306");
        String database = ConfigUtil.get("db.database");
        String params = ConfigUtil.get("db.params", "");
        String baseUrl = String.format("jdbc:mysql://%s:%s/?%s", host, port, params);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(baseUrl);
        config.setUsername(ConfigUtil.get("db.username"));
        config.setPassword(ConfigUtil.get("db.password"));
        config.setMaximumPoolSize(2);
        config.setConnectionTimeout(30000);
        config.setPoolName("KaiBoTiXingInitPool");
        config.setConnectionTestQuery("SELECT 1");

        try (HikariDataSource initDs = new HikariDataSource(config);
             Connection conn = initDs.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + database
                    + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            log.info("数据库 {} 已就绪", database);
        } catch (Exception e) {
            log.warn("自动创建数据库失败（可能已存在或无权限）: {}", e.getMessage());
        }
    }

    private static void initSchema() {
        try (Connection conn = dataSource.getConnection();
             InputStream in = DataSourceManager.class.getClassLoader()
                     .getResourceAsStream("config/schema.sql")) {
            if (in == null) {
                log.warn("未找到建表脚本 config/schema.sql，跳过自动建表");
                return;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                        continue;
                    }
                    sb.append(line).append('\n');
                }
            }
            try (Statement stmt = conn.createStatement()) {
                for (String sql : sb.toString().split(";")) {
                    if (!sql.isBlank()) {
                        stmt.execute(sql);
                    }
                }
            }
            log.info("数据库建表脚本执行完成");
        } catch (Exception e) {
            log.error("执行建表脚本失败", e);
        }
    }

    public static synchronized void close() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
            log.info("数据库连接池已关闭");
        }
    }
}
