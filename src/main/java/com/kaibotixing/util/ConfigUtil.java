package com.kaibotixing.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 配置读取工具：从 classpath 下的 config/db.properties 加载。
 */
public final class ConfigUtil {

    private static final Logger log = LoggerFactory.getLogger(ConfigUtil.class);
    private static final String CONFIG_FILE = "config/db.properties";
    private static final Properties PROPS = new Properties();

    static {
        try (InputStream in = ConfigUtil.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (in != null) {
                PROPS.load(in);
                log.info("配置文件加载成功: {}", CONFIG_FILE);
            } else {
                log.warn("未找到配置文件: {}", CONFIG_FILE);
            }
        } catch (IOException e) {
            log.error("加载配置文件失败", e);
        }
    }

    private ConfigUtil() {
    }

    public static String get(String key) {
        return PROPS.getProperty(key);
    }

    public static String get(String key, String defaultValue) {
        return PROPS.getProperty(key, defaultValue);
    }

    public static int getInt(String key, int defaultValue) {
        String v = PROPS.getProperty(key);
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
        String v = PROPS.getProperty(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
    }
}
