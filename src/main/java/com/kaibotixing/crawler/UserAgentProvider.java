package com.kaibotixing.crawler;

import com.kaibotixing.util.ConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 浏览器 User-Agent 池：内置主流浏览器 UA（Chrome/Edge/Firefox/Safari，
 * 含 Windows/macOS/Android 平台），支持通过配置追加自定义 UA，随机选取。
 */
public class UserAgentProvider {

    private static final Logger log = LoggerFactory.getLogger(UserAgentProvider.class);

    /** 内置主流浏览器 UA */
    private static final List<String> BUILTIN_UAS = List.of(
            // Chrome Windows
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            // Chrome macOS
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            // Chrome Android
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
            // Edge Windows
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36 Edg/125.0.0.0",
            // Edge macOS
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0",
            // Firefox Windows
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:126.0) Gecko/20100101 Firefox/126.0",
            // Firefox macOS
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:127.0) Gecko/20100101 Firefox/127.0",
            // Safari macOS
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15");

    private final List<String> uas;

    public UserAgentProvider() {
        this(ConfigUtil.get("crawler.user.agents", ""));
    }

    /**
     * @param custom 自定义 UA，多个以 {@code |} 分隔（UA 字符串中不含该字符，安全）
     */
    public UserAgentProvider(String custom) {
        List<String> list = new ArrayList<>(BUILTIN_UAS);
        if (custom != null && !custom.isBlank()) {
            for (String s : custom.split("\\|")) {
                String t = s.trim();
                if (!t.isEmpty() && !list.contains(t)) {
                    list.add(t);
                }
            }
        }
        this.uas = List.copyOf(list);
        log.info("User-Agent 池初始化完成，共 {} 个 UA", uas.size());
    }

    /** 随机返回一个 UA（线程安全） */
    public String random() {
        return uas.get(ThreadLocalRandom.current().nextInt(uas.size()));
    }

    /** 池中 UA 数量 */
    public int size() {
        return uas.size();
    }
}
