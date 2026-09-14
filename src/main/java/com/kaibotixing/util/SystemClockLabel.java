package com.kaibotixing.util;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Label;
import javafx.util.Duration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 顶栏系统时间标签：红色加粗显示当前系统时间（yyyy-MM-dd HH:mm:ss），每秒自动刷新。
 * <p>
 * 使用秒级 {@link Timeline} 驱动，只做界面更新，不引入额外线程；窗口隐藏到托盘时仍会运行，
 * 但开销可忽略。使用完毕后可调用 {@link #stop()} 释放定时器。
 * </p>
 */
public class SystemClockLabel extends Label {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Timeline timeline;

    public SystemClockLabel() {
        // 等宽字体 + 红色加粗，避免秒数变化时标签宽度抖动
        setStyle("-fx-text-fill: #e74c3c; -fx-font-weight: bold;"
                + " -fx-font-family: 'Consolas', 'Microsoft YaHei', monospace;"
                + " -fx-font-size: 14px;");
        setText(now());

        timeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> setText(now())));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
    }

    private static String now() {
        return LocalDateTime.now().format(FORMATTER);
    }

    /**
     * 停止秒级刷新定时器。
     */
    public void stop() {
        timeline.stop();
    }
}
