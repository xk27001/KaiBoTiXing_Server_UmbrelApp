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
import java.util.regex.Pattern;

/**
 * 数据源管理器：基于 HikariCP 连接池，启动时自动执行建表脚本。
 */
public final class DataSourceManager {

    private static final Logger log = LoggerFactory.getLogger(DataSourceManager.class);
    private static final Pattern DATABASE_NAME = Pattern.compile("[A-Za-z0-9_$]+");
    private static final String DEFAULT_JDBC_PARAMS =
            "useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai"
                    + "&sslMode=REQUIRED&useSSL=true&requireSSL=true&allowPublicKeyRetrieval=false";

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
        if (ConfigUtil.getBoolean("db.auto.create", false)) {
            ensureDatabaseExists();
        }

        HikariConfig config = createConfig(
                "KaiBoTiXingPool",
                ConfigUtil.getInt("db.pool.size", 8),
                ConfigUtil.getInt("db.pool.timeout", 30000),
                true);
        dataSource = new HikariDataSource(config);
        log.info("数据库连接池初始化完成");

        initSchema();
    }

    private static HikariConfig createConfig(
            String poolName, int maxPoolSize, int connectionTimeout, boolean includeDatabase) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(buildJdbcUrl(includeDatabase));
        config.setUsername(required("db.username"));
        config.setPassword(required("db.password"));
        config.setMaximumPoolSize(Math.max(1, maxPoolSize));
        config.setConnectionTimeout(Math.max(250, connectionTimeout));
        config.setPoolName(poolName);
        config.setConnectionTestQuery("SELECT 1");
        return config;
    }

    private static String buildJdbcUrl(boolean includeDatabase) {
        String host = required("db.host");
        int port = parsePort(ConfigUtil.get("db.port", "3306"));
        String database = includeDatabase ? databaseName() : "";
        String params = ConfigUtil.get("db.params", DEFAULT_JDBC_PARAMS);
        if (params == null || params.isBlank()) {
            params = DEFAULT_JDBC_PARAMS;
        }
        return String.format("jdbc:mysql://%s:%d/%s?%s", host, port, database, params);
    }

    /**
     * 若目标数据库不存在则自动创建（连接不带库名的 URL）。阿里云 RDS 建议预先建库并关闭此功能。
     */
    private static void ensureDatabaseExists() {
        String database = databaseName();
        HikariConfig config = createConfig("KaiBoTiXingInitPool", 2, 30000, false);
        try (HikariDataSource initDs = new HikariDataSource(config);
             Connection conn = initDs.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + database
                    + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
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
                throw new IllegalStateException("未找到建表脚本 config/schema.sql");
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
            close();
            throw new IllegalStateException("执行建表脚本失败", e);
        }
    }

    private static String databaseName() {
        String database = required("db.database");
        if (!DATABASE_NAME.matcher(database).matches()) {
            throw new IllegalStateException("db.database 只能包含字母、数字、下划线和 $");
        }
        return database;
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value == null ? "" : value.trim());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("db.port 必须是 1 到 65535 之间的整数");
        }
    }

    private static String required(String key) {
        String value = ConfigUtil.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少数据库配置: " + key);
        }
        return value.trim();
    }

    public static synchronized void close() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
            log.info("数据库连接池已关闭");
        }
    }
}
