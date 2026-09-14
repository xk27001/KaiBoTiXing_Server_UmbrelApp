package com.kaibotixing.ui;

import com.kaibotixing.crawler.ProxyPoolService;
import com.kaibotixing.util.AppIcon;
import com.kaibotixing.util.TableSelectionKeeper;
import com.kaibotixing.util.TimeUtil;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Proxy;
import java.util.List;

/**
 * 代理池详情窗口：实时展示代理池的处理过程（数据源拉取、连通性验证进度）、
 * 可用代理列表，并提供「立即刷新」「清空事件」操作。
 * 内部每 2 秒轮询一次 {@link ProxyPoolService} 的状态快照，仅做展示，不持有代理池生命周期。
 */
public class ProxyPoolWindow extends Stage {

    private static final Logger log = LoggerFactory.getLogger(ProxyPoolWindow.class);

    private final ProxyPoolService proxyPool;
    private Timeline timer;

    private final Label lblStatus = new Label();
    private final Label lblProgress = new Label();
    private final TextArea taEvents = new TextArea();
    private final ObservableList<Proxy> proxyList = FXCollections.observableArrayList();
    private final TableView<Proxy> proxyTable = new TableView<>();
    private String lastEventsFingerprint = "";

    public ProxyPoolWindow(ProxyPoolService proxyPool) {
        this.proxyPool = proxyPool;
        setTitle("代理池详情");
        buildUi();
        startTimer();
    }

    private void buildUi() {
        taEvents.setEditable(false);
        taEvents.setWrapText(false);
        taEvents.setPromptText("代理池处理过程将实时显示在这里…");
        taEvents.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px;");

        TableColumn<Proxy, String> colAddr = new TableColumn<>("代理地址");
        colAddr.setPrefWidth(260);
        colAddr.setCellValueFactory(c -> new SimpleStringProperty(addressOf(c.getValue())));
        TableColumn<Proxy, String> colType = new TableColumn<>("类型");
        colType.setPrefWidth(120);
        colType.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().type().name()));
        TableColumn<Proxy, String> colStatus = new TableColumn<>("状态");
        colStatus.setPrefWidth(120);
        colStatus.setCellValueFactory(c -> new SimpleStringProperty("已验证可用"));
        proxyTable.getColumns().addAll(colAddr, colType, colStatus);
        proxyTable.setItems(proxyList);
        proxyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        proxyTable.setPlaceholder(new Label("暂无可用代理"));

        Button btnRefresh = new Button("立即刷新");
        btnRefresh.setOnAction(e -> {
            if (!proxyPool.refreshNow()) {
                showHint("上一轮刷新仍在进行或代理池未启用，请稍候");
            }
        });
        Button btnClear = new Button("清空事件");
        btnClear.setOnAction(e -> {
            proxyPool.clearEvents();
            taEvents.clear();
            lastEventsFingerprint = "";
        });
        Button btnClose = new Button("关闭");
        btnClose.setOnAction(e -> close());

        HBox top = new HBox(10, btnRefresh, btnClear, btnClose);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(10));
        top.setStyle("-fx-background-color: #f4f4f4; -fx-border-color: #dddddd; -fx-border-width: 0 0 1 0;");

        Label lblEvents = new Label("处理过程（拉取 / 验证）");
        lblEvents.setStyle("-fx-font-weight: bold;");
        Label lblAvail = new Label("可用代理列表");
        lblAvail.setStyle("-fx-font-weight: bold;");

        VBox center = new VBox(6, lblStatus, lblProgress, lblEvents, taEvents, lblAvail, proxyTable);
        center.setPadding(new Insets(10));
        VBox.setVgrow(taEvents, Priority.ALWAYS);
        VBox.setVgrow(proxyTable, Priority.ALWAYS);
        taEvents.setMaxHeight(Double.MAX_VALUE);
        proxyTable.setMaxHeight(Double.MAX_VALUE);

        BorderPane root = new BorderPane(center);
        root.setTop(top);
        setScene(new Scene(root, 760, 580));
        // 独立窗口图标
        AppIcon.apply(this);
    }

    private void startTimer() {
        timer = new Timeline(new KeyFrame(Duration.seconds(2), e -> refresh()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    private void refresh() {
        try {
            ProxyPoolService.ProxyStatus st = proxyPool.getStatus();
            ProxyPoolService.ValidationProgress vp = proxyPool.getValidationProgress();

            // 状态行
            if (!st.enabled()) {
                lblStatus.setText("代理池未启用（crawler.proxy.enabled=false），爬取将使用直连");
                lblStatus.setTextFill(Color.GRAY);
            } else if (st.available() > 0) {
                lblStatus.setText(String.format("可用代理：%d 个", st.available()));
                lblStatus.setTextFill(Color.web("#1a7f37"));
            } else if (vp.inProgress()) {
                lblStatus.setText(String.format("正在验证代理…（可用 0，已验证 %d/%d）", vp.done(), vp.total()));
                lblStatus.setTextFill(Color.ORANGE);
            } else {
                lblStatus.setText("可用代理：0 个（直连兜底中）");
                lblStatus.setTextFill(Color.ORANGE);
            }

            // 进度/概要行
            String lastRefresh = st.lastRefresh() != null ? TimeUtil.format(st.lastRefresh()) : "--";
            if (vp.inProgress()) {
                lblProgress.setText(String.format("候选 %d 条 · 验证 %d/%d · 上次刷新 %s",
                        st.candidateCount(), vp.done(), vp.total(), lastRefresh));
            } else {
                lblProgress.setText(String.format("候选 %d 条 · 上次刷新 %s",
                        st.candidateCount(), lastRefresh));
            }

            // 事件日志（仅在内容变化时刷新，避免滚动条抖动）
            List<String> events = proxyPool.getRecentEvents();
            String joined = String.join("\n", events);
            if (!joined.equals(lastEventsFingerprint)) {
                lastEventsFingerprint = joined;
                taEvents.setText(joined);
                Platform.runLater(() -> taEvents.setScrollTop(Double.MAX_VALUE));
            }

            // 刷新代理列表并保持选中行（key = 类型@地址）
            TableSelectionKeeper.refresh(proxyTable, proxyPool.getAvailableProxies(),
                    p -> p.type().name() + "@" + addressOf(p));
        } catch (Exception e) {
            log.warn("刷新代理池窗口失败: {}", e.getMessage());
        }
    }

    private static String addressOf(Proxy p) {
        return p != null && p.address() != null
                ? p.address().toString().replace("/", "") : "";
    }

    private void showHint(String msg) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, msg);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            AppIcon.apply(alert);
            alert.show();
        });
    }

    @Override
    public void close() {
        if (timer != null) {
            timer.stop();
        }
        super.close();
    }
}
