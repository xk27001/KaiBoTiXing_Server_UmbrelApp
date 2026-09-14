package com.kaibotixing.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 时间格式化工具。
 */
public final class TimeUtil {

    public static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TimeUtil() {
    }

    public static String format(LocalDateTime time) {
        return time == null ? "" : time.format(FORMATTER);
    }
}
