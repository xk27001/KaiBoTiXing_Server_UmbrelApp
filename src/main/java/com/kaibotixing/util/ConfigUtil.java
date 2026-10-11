package com.kaibotixing.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * 配置读取工具：从 classpath 或外部 db.properties 加载，并允许通过
 * JVM 系统属性或 KBTX_* 环境变量覆盖单个配置项。
 *
 * <p>例如 {@code db.host} 可分别通过 {@code -Dkaibotixing.db.host=...}、
 * {@code -Ddb.host=...} 或环境变量 {@code KBTX_DB_HOST} 覆盖。容器部署优先使用环境变量，
 * 避免把数据库密码写入镜像。连接阿里云 RDS 时，推荐挂载只读的 db.properties。</p>
 */
public final class ConfigUtil {

    private static final Logger log = LoggerFactory.getLogger(ConfigUtil.class);
    private static final String CONFIG_FILE = "config/db.properties";
    private static final String PREFIX = "KBTX_";
    private static final Properties PROPS = new Properties();

    static {
        try (InputStream in = ConfigUtil.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (in != null) {
                PROPS.load(in);
                log.info("classpath 配置文件加载成功: {}", CONFIG_FILE);
            }
        } catch (IOException e) {
            log.error("加载 classpath 配置文件失败", e);
        }
        loadExternalConfig();
    }

    /**
     * 加载容器外部配置文件。可通过 KBTX_CONFIG_FILE 指定路径；
     * Docker 默认读取 /app/config/db.properties。
     */
    private static void loadExternalConfig() {
        String configured = System.getProperty("kaibotixing.config.file");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("KBTX_CONFIG_FILE");
        }
        Path path = (configured == null || configured.isBlank())
                ? Path.of("config", "db.properties")
                : Path.of(configured);
        if (!Files.isRegularFile(path)) {
            log.info("未找到外部配置文件 {}，将使用 classpath、环境变量和内置默认值", path);
            return;
        }
        try (InputStream in = Files.newInputStream(path)) {
            PROPS.load(in);
            log.info("外部配置文件加载成功: {}", path.toAbsolutePath());
        } catch (IOException e) {
            log.error("加载外部配置文件失败: {}", path, e);
        }
    }
    private ConfigUtil() {
    }

    public static String get(String key) {
        return get(key, null);
    }

    public static String get(String key, String defaultValue) {
        String value = externalValue(key);
        if (value == null) {
            value = PROPS.getProperty(key);
        }
        return value != null ? value : defaultValue;
    }

    public static int getInt(String key, int defaultValue) {
        String v = get(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        String v = get(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
    }

    /**
     * 系统属性优先于环境变量；两种来源都不存在时返回 null。
     */
    private static String externalValue(String key) {
        String namespaced = System.getProperty("kaibotixing." + key);
        if (namespaced != null) {
            return namespaced;
        }
        String plain = System.getProperty(key);
        if (plain != null) {
            return plain;
        }
        return System.getenv(PREFIX + key.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "_"));
    }
}
