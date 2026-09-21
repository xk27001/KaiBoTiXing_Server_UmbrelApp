package com.kaibotixing.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

/**
 * 配置读取工具：从 classpath 下的 config/db.properties 加载，并允许通过
 * JVM 系统属性或 KBTX_* 环境变量覆盖单个配置项。
 *
 * <p>例如 {@code db.host} 可分别通过 {@code -Dkaibotixing.db.host=...}、
 * {@code -Ddb.host=...} 或环境变量 {@code KBTX_DB_HOST} 覆盖。容器部署优先使用环境变量，
 * 避免把数据库密码写入镜像或配置文件。</p>
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
                log.info("配置文件加载成功: {}", CONFIG_FILE);
            } else {
                log.info("未找到配置文件 {}，将使用环境变量和内置默认值", CONFIG_FILE);
            }
        } catch (IOException e) {
            log.error("加载配置文件失败", e);
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
