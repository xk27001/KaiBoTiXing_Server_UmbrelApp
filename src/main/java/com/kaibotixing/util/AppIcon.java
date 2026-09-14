package com.kaibotixing.util;

import javafx.event.EventHandler;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.DialogPane;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用图标工具：程序内所有窗体（主窗口、代理池窗口、各类弹窗）统一使用同一个图标。
 * <p>
 * 图标不依赖外部图片资源，直接用 AWT {@link Graphics2D} 绘制「红色圆形 + 白色播放三角」
 * （象征开播），并按需转换为 JavaFX {@link Image} 供 {@link javafx.stage.Stage} / {@link Dialog} 使用，
 * 同时供系统托盘复用，避免打包时遗漏图片资源导致的图标丢失。
 * </p>
 */
public final class AppIcon {

    /** 图标尺寸（从大到小，JavaFX 会按窗口标题栏/任务栏实际需求挑选） */
    private static final int[] SIZES = {64, 48, 32, 16};

    private static final java.awt.Color RING_COLOR = new java.awt.Color(0x9b, 0x21, 0x1c);
    private static final java.awt.Color MAIN_COLOR = new java.awt.Color(0xe7, 0x4c, 0x3c);

    /** JavaFX 图标列表缓存 */
    private static volatile List<Image> fxIcons;
    /** AWT 图标缓存（托盘使用），key 为尺寸 */
    private static final Map<Integer, java.awt.Image> AWT_ICONS = new HashMap<>();

    private AppIcon() {
    }

    /**
     * 获取 JavaFX 图标列表（懒加载并缓存，多尺寸）。
     */
    public static List<Image> icons() {
        List<Image> local = fxIcons;
        if (local == null) {
            synchronized (AppIcon.class) {
                local = fxIcons;
                if (local == null) {
                    List<Image> list = new ArrayList<>(SIZES.length);
                    for (int size : SIZES) {
                        list.add(toFxImage(draw(size)));
                    }
                    local = List.copyOf(list);
                    fxIcons = local;
                }
            }
        }
        return local;
    }

    /**
     * 获取指定尺寸的 AWT 图像（供 dorkbox 系统托盘使用）。
     */
    public static synchronized java.awt.Image awtIcon(int size) {
        return AWT_ICONS.computeIfAbsent(size, AppIcon::draw);
    }

    /**
     * 为窗口（Stage）设置图标。
     */
    public static void apply(Stage stage) {
        if (stage == null) {
            return;
        }
        try {
            stage.getIcons().setAll(icons());
        } catch (Exception ignored) {
            // 少数窗口类型不支持设置图标，忽略即可
        }
    }

    /**
     * 按窗口对象设置图标（仅 Stage 支持图标，其余忽略）。
     */
    private static void applyWindow(Window window) {
        if (window instanceof Stage stage) {
            apply(stage);
        }
    }

    /**
     * 为对话框（Alert / Dialog）设置图标。
     * <p>
     * 对话框的 Scene 在显示前可能尚未创建，因此未显示时挂载到 onShown 回调，
     * 显示后再设置图标；已显示的直接设置。原有 onShown 回调会被保留并继续执行。
     * </p>
     */
    public static void apply(Dialog<?> dialog) {
        if (dialog == null) {
            return;
        }
        DialogPane pane = dialog.getDialogPane();
        if (pane != null && pane.getScene() != null) {
            applyWindow(pane.getScene().getWindow());
            return;
        }

        EventHandler<DialogEvent> previous = dialog.getOnShown();
        dialog.setOnShown(event -> {
            if (previous != null) {
                previous.handle(event);
            }
            if (pane != null && pane.getScene() != null) {
                applyWindow(pane.getScene().getWindow());
            }
        });
    }

    /**
     * 绘制图标：深色外环 + 红色圆底 + 白色播放三角。
     */
    private static BufferedImage draw(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // 外环
        int outerPad = Math.max(0, Math.round(size * 0.03f));
        int outerSize = size - outerPad * 2;
        g.setColor(RING_COLOR);
        g.fillOval(outerPad, outerPad, outerSize, outerSize);

        // 红色圆底
        int innerPad = Math.max(0, Math.round(size * 0.11f));
        int innerSize = size - innerPad * 2;
        g.setColor(MAIN_COLOR);
        g.fillOval(innerPad, innerPad, innerSize, innerSize);

        // 白色播放三角
        g.setColor(java.awt.Color.WHITE);
        int[] xs = {Math.round(size * 0.40f), Math.round(size * 0.40f), Math.round(size * 0.72f)};
        int[] ys = {Math.round(size * 0.30f), Math.round(size * 0.70f), Math.round(size * 0.50f)};
        g.fillPolygon(xs, ys, 3);

        g.dispose();
        return img;
    }

    /**
     * AWT 图像转 JavaFX 图像（不依赖 javafx.swing 模块）。
     */
    private static Image toFxImage(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        WritableImage dst = new WritableImage(w, h);
        PixelWriter writer = dst.getPixelWriter();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            src.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                writer.setArgb(x, y, row[x]);
            }
        }
        return dst;
    }
}
