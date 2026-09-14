package com.kaibotixing.model;

/**
 * 直播状态枚举。
 */
public enum LiveStatus {
    /** 直播中 */
    LIVE("直播中"),
    /** 未开播 */
    OFFLINE("未开播"),
    /** 未知/异常 */
    UNKNOWN("未知");

    private final String label;

    LiveStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static LiveStatus from(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        try {
            return LiveStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
