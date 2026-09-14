package com.kaibotixing.service;

import com.kaibotixing.util.AppIcon;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.media.AudioClip;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;

/**
 * 开播提醒服务：检测到开播时弹出红色背景通知窗口并播放提示音。
 * 所有 UI 操作通过 Platform.runLater 切回 FX 线程。
 */
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private volatile AudioClip audioClip;
    private boolean soundLoaded = false;

    /**
     * 弹出开播提醒：红色背景，第一行黑色加粗主播名，第二行「xxx直播间开播了」，
     * 下方为放大的「确认」按钮。
     */
    public void alert(String nickname, String roomId) {
        Platform.runLater(() -> {
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("开播提醒");
            dialog.setHeaderText(null);

            // 内容：两行文字
            Label nameLabel = new Label(nickname == null ? "" : nickname);
            nameLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: black;");

            Label msgLabel = new Label((roomId == null || roomId.isBlank() ? "" : roomId) + "直播间开播了");
            msgLabel.setStyle("-fx-font-size: 16px; -fx-text-fill: black;");

            VBox content = new VBox(10, nameLabel, msgLabel);
            content.setAlignment(Pos.CENTER_LEFT);
            content.setPadding(new Insets(15));

            DialogPane pane = dialog.getDialogPane();
            pane.setContent(content);
            // 红色背景
            pane.setStyle("-fx-background-color: #e74c3c; -fx-background: #e74c3c;");

            // 放大的「确认」按钮
            ButtonType confirm = new ButtonType("确认", ButtonBar.ButtonData.OK_DONE);
            pane.getButtonTypes().add(confirm);

            // 提醒弹窗图标
            AppIcon.apply(dialog);

            dialog.show();

            // 弹窗置顶显示
            if (pane.getScene() != null && pane.getScene().getWindow() instanceof Stage stage) {
                stage.setAlwaysOnTop(true);
                stage.toFront();
            }

            // 放大确认按钮（必须在 dialog.show() 后 lookup）
            Button okBtn = (Button) pane.lookupButton(confirm);
            if (okBtn != null) {
                okBtn.setPrefSize(120, 48);
                okBtn.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-background-color: white; -fx-text-fill: #e74c3c;");
            }
        });

        playSound();
    }

    private synchronized void playSound() {
        try {
            if (!soundLoaded) {
                URL url = getClass().getClassLoader().getResource("sound/notify.wav");
                if (url != null) {
                    audioClip = new AudioClip(url.toExternalForm());
                    soundLoaded = true;
                } else {
                    log.warn("未找到提示音资源 sound/notify.wav");
                }
            }
            if (audioClip != null) {
                audioClip.play();
            }
        } catch (Exception e) {
            log.warn("播放提示音失败: {}", e.getMessage());
        }
    }
}
