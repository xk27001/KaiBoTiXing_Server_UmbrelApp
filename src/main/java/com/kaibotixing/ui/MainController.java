package com.kaibotixing.ui;

import com.kaibotixing.crawler.DouyinWebCrawler;
import com.kaibotixing.crawler.ProxyPoolService;
import com.kaibotixing.dao.AnchorLiveSessionDao;
import com.kaibotixing.dao.MonitorConfigDao;
import com.kaibotixing.model.Anchor;
import com.kaibotixing.model.AnchorLiveSession;
import com.kaibotixing.model.LiveStatus;
import com.kaibotixing.model.MonitorLog;
import com.kaibotixing.scheduler.MonitorScheduler;
import com.kaibotixing.service.AlertService;
import com.kaibotixing.service.AnchorService;
import com.kaibotixing.service.AutoStartService;
import com.kaibotixing.service.LogService;
import com.kaibotixing.util.AppIcon;
import com.kaibotixing.util.ColumnWidthStore;
import com.kaibotixing.util.SystemClockLabel;
import com.kaibotixing.util.TableSelectionKeeper;
import com.kaibotixing.util.TimeUtil;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 主界面控制器：主播管理、监控状态、日志查看、监控启停控制。
 */
public class MainController {

    private static final Logger log = LoggerFactory.getLogger(MainController.class);

    private final AnchorService anchorService = new AnchorService();
    private final LogService logService = new LogService();
    private final AlertService alertService = new AlertService();
    private final AutoStartService autoStartService = new AutoStartService();
    private final MonitorConfigDao configDao = new MonitorConfigDao();
    private final MonitorScheduler scheduler;
    /** 共享代理池实例（由本控制器管理生命周期，注入爬取器使用） */
    private final ProxyPoolService proxyPool;

    private final TabPane tabPane = new TabPane();

    /** 是否以自启动方式启动（--auto-start 参数），启动后自动开启监控 */
    private boolean autoStart;
    /** 5 秒周期刷新定时器 */
    private Timeline refreshTimer;
    /** 界面根节点（单例下只构建一次，避免重复创建 Tab 与定时器） */
    private Parent root;

    // 主播管理组件
    private final ObservableList<Anchor> anchorList = FXCollections.observableArrayList();
    private final TableView<Anchor> anchorTable = new TableView<>();
    private final TextField tfNickname = new TextField();
    private final TextField tfDouyinId = new TextField();
    private final TextField tfWebRid = new TextField();
    private final TextField tfHomeUrl = new TextField();
    private final TextField tfRemark = new TextField();
    private final CheckBox cbEnabled = new CheckBox("启用监控");

    // 监控状态组件
    private final ObservableList<Anchor> statusList = FXCollections.observableArrayList();
    private final TableView<Anchor> statusTable = new TableView<>();

    // 日志组件
    private final ObservableList<MonitorLog> logList = FXCollections.observableArrayList();
    private final TableView<MonitorLog> logTable = new TableView<>();

    // 开播记录组件
    private final AnchorLiveSessionDao sessionDao = new AnchorLiveSessionDao();
    private final ObservableList<AnchorLiveSession> sessionList = FXCollections.observableArrayList();
    private final TableView<AnchorLiveSession> sessionTable = new TableView<>();

    // 启停控制
    private final Button btnStart = new Button("启动监控");
    private final Button btnStop = new Button("停止监控");
    private final Label lblStatus = new Label("监控状态：已停止");
    private final Label lblProxyStatus = new Label("代理池：初始化中…");
    /** 顶栏系统时间（红色显示，每秒刷新） */
    private final SystemClockLabel lblClock = new SystemClockLabel();
    /** 代理池详情窗口（懒创建，关闭后可重新打开） */
    private ProxyPoolWindow proxyWindow;

    /** 单例实例（懒加载 + 双重检查锁） */
    private static volatile MainController instance;

    /**
     * 获取全局唯一控制器实例（单例）。
     * <p>
     * 构造时会启动代理池与监控调度器，因此重复调用 {@link #getInstance()} 不会重复初始化。
     * </p>
     */
    public static MainController getInstance() {
        MainController local = instance;
        if (local == null) {
            synchronized (MainController.class) {
                local = instance;
                if (local == null) {
                    local = new MainController();
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * 私有构造：只能通过 {@link #getInstance()} 创建，保证全局仅有一个控制器
     * （也保证代理池、调度器、刷新定时器各只有一份）。
     */
    private MainController() {
        this.proxyPool = new ProxyPoolService();
        this.proxyPool.start();
        this.scheduler = new MonitorScheduler(new DouyinWebCrawler(proxyPool), logService, alertService);
        loadSwitchConfig();
    }

    /**
     * 设置是否以开机自启动方式启动（需在 {@link #getRoot()} 之前调用）。
     */
    public void setAutoStart(boolean autoStart) {
        this.autoStart = autoStart;
    }

    /**
     * 从数据库读取开关初始状态，同步到调度器与日志服务。
     */
    private void loadSwitchConfig() {
        try {
            boolean alertOn = !"0".equals(configDao.get("alert.enabled", "1"));
            boolean logOn = !"0".equals(configDao.get("log.enabled", "1"));
            scheduler.setAlertEnabled(alertOn);
            logService.setLogEnabled(logOn);
        } catch (Exception e) {
            log.warn("读取开关配置失败，使用默认值（均开启）: {}", e.getMessage());
            scheduler.setAlertEnabled(true);
            logService.setLogEnabled(true);
        }
    }

    public Parent getRoot() {
        // 单例：界面只构建一次，重复调用直接返回同一根节点
        if (root != null) {
            return root;
        }

        tabPane.getTabs().addAll(
                new Tab("监控状态", buildStatusTab()),
                new Tab("主播管理", buildAnchorTab()),
                new Tab("开播记录", buildSessionTab()),
                new Tab("日志查看", buildLogTab()));

        BorderPane pane = new BorderPane(tabPane);
        pane.setTop(buildTopBar());

        // 初始化后加载历史日志与主播状态，并启动 5 秒定时刷新
        refreshLogs();
        refreshAnchorTable();
        refreshStatusTable();
        refreshSessions();
        updateProxyStatusLabel();
        startRefreshTimer();

        // 自启动场景：自动开启监控
        if (autoStart) {
            Platform.runLater(() -> {
                scheduler.start();
                btnStart.setDisable(true);
                btnStop.setDisable(false);
                updateStatusLabel();
                logService.info("控制", "软件随开机自启动，已自动开启监控");
            });
        }

        root = pane;
        return root;
    }

    /**
     * 启动 5 秒周期刷新定时器，刷新监控状态与日志列表。
     */
    private void startRefreshTimer() {
        if (refreshTimer != null) {
            return;
        }
        refreshTimer = new Timeline(new KeyFrame(Duration.seconds(5), e -> {
            refreshStatusTable();
            refreshLogs();
            refreshSessions();
            updateProxyStatusLabel();
        }));
        refreshTimer.setCycleCount(Timeline.INDEFINITE);
        refreshTimer.play();
    }

    private Parent buildTopBar() {
        btnStart.setOnAction(e -> {
            scheduler.start();
            btnStart.setDisable(true);
            btnStop.setDisable(false);
            updateStatusLabel();
            logService.info("控制", "用户点击启动监控");
        });
        btnStop.setOnAction(e -> {
            scheduler.stop();
            btnStop.setDisable(true);
            btnStart.setDisable(false);
            updateStatusLabel();
            logService.info("控制", "用户点击停止监控");
        });
        btnStop.setDisable(true);

        // 开机自启动复选框
        CheckBox cbAutoStart = new CheckBox("开机自启动");
        boolean enabled = autoStartService.isEnabled();
        cbAutoStart.setSelected(enabled);
        cbAutoStart.setOnAction(e -> {
            boolean want = cbAutoStart.isSelected();
            if (want && !autoStartService.isExeEnvironment()) {
                showWarn("当前为开发环境（jar 运行），开机自启动仅在打包后的 exe 版本中生效。");
                cbAutoStart.setSelected(false);
                return;
            }
            boolean ok = want ? autoStartService.enable() : autoStartService.disable();
            if (ok) {
                logService.info("控制", want ? "已开启开机自启动" : "已关闭开机自启动");
            } else {
                showError((want ? "开启" : "关闭") + "开机自启动失败，请检查系统权限");
                cbAutoStart.setSelected(!want);
            }
        });

        // 弹窗提醒开关
        CheckBox cbAlert = new CheckBox("弹窗提醒");
        cbAlert.setSelected(readSwitch("alert.enabled", true));
        cbAlert.setOnAction(e -> {
            boolean want = cbAlert.isSelected();
            scheduler.setAlertEnabled(want);
            saveSwitch("alert.enabled", want);
            logService.info("控制", want ? "已开启弹窗提醒" : "已关闭弹窗提醒");
        });

        // 日志记录开关（仅控制数据库日志，本地文件日志不受影响）
        CheckBox cbLog = new CheckBox("日志记录");
        cbLog.setSelected(readSwitch("log.enabled", true));
        cbLog.setOnAction(e -> {
            boolean want = cbLog.isSelected();
            logService.setLogEnabled(want);
            saveSwitch("log.enabled", want);
            logService.info("控制", want ? "已开启日志记录" : "已关闭日志记录（本地文件日志仍保留）");
        });

        // 代理池详情窗口按钮
        Button btnProxy = new Button("代理池详情");
        btnProxy.setOnAction(e -> openProxyWindow());

        // 右侧弹性填充，使系统时间始终靠右显示
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(10, btnStart, btnStop, lblStatus, new Label("|"),
                cbAlert, cbLog, new Label("|"), cbAutoStart, new Label("|"), lblProxyStatus,
                btnProxy, spacer, lblClock);
        bar.setPadding(new Insets(10));
        bar.setStyle("-fx-background-color: #f4f4f4; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return bar;
    }

    /**
     * 打开（或聚焦）代理池详情窗口；窗口被关闭后再次点击会重建。
     */
    private void openProxyWindow() {
        if (proxyWindow == null || !proxyWindow.isShowing()) {
            if (proxyWindow != null) {
                proxyWindow.close();
            }
            proxyWindow = new ProxyPoolWindow(proxyPool);
        }
        proxyWindow.show();
        proxyWindow.toFront();
        proxyWindow.requestFocus();
    }

    private boolean readSwitch(String key, boolean defaultValue) {
        try {
            return !"0".equals(configDao.get(key, defaultValue ? "1" : "0"));
        } catch (Exception e) {
            log.warn("读取开关配置 {} 失败: {}", key, e.getMessage());
            return defaultValue;
        }
    }

    private void saveSwitch(String key, boolean value) {
        try {
            configDao.set(key, value ? "1" : "0");
        } catch (Exception e) {
            log.warn("保存开关配置 {} 失败: {}", key, e.getMessage());
        }
    }

    private void updateStatusLabel() {
        if (scheduler.isRunning()) {
            lblStatus.setText("监控状态：运行中");
            lblStatus.setTextFill(Color.GREEN);
        } else {
            lblStatus.setText("监控状态：已停止");
            lblStatus.setTextFill(Color.RED);
        }
    }

    /**
     * 更新顶部栏代理池状态显示（可用数量 + 最近刷新时间）。
     */
    private void updateProxyStatusLabel() {
        ProxyPoolService.ProxyStatus status = proxyPool.getStatus();
        if (!status.enabled()) {
            lblProxyStatus.setText("代理池：未启用");
            lblProxyStatus.setTextFill(Color.GRAY);
        } else if (status.available() == 0) {
            lblProxyStatus.setText("代理池：暂无可用（直连中）");
            lblProxyStatus.setTextFill(Color.ORANGE);
        } else {
            String time = status.lastRefresh() != null
                    ? TimeUtil.format(status.lastRefresh()) : "--";
            lblProxyStatus.setText(String.format("代理池：可用 %d · 刷新 %s", status.available(), time));
            lblProxyStatus.setTextFill(Color.BLUE);
        }
    }

    // ==================== 主播管理 Tab ====================

    private Parent buildAnchorTab() {
        TableColumn<Anchor, String> colNickname = new TableColumn<>("昵称");
        colNickname.setCellValueFactory(new PropertyValueFactory<>("nickname"));
        TableColumn<Anchor, String> colDouyinId = new TableColumn<>("抖音号/主页");
        colDouyinId.setCellValueFactory(new PropertyValueFactory<>("douyinId"));
        TableColumn<Anchor, String> colWebRid = new TableColumn<>("房间号");
        colWebRid.setCellValueFactory(new PropertyValueFactory<>("webRid"));
        TableColumn<Anchor, String> colRemark = new TableColumn<>("备注");
        colRemark.setCellValueFactory(new PropertyValueFactory<>("remark"));
        TableColumn<Anchor, String> colEnabled = new TableColumn<>("启用");
        colEnabled.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(
                cell.getValue().isEnabled() ? "是" : "否"));
        TableColumn<Anchor, String> colLastStatus = new TableColumn<>("最新状态");
        colLastStatus.setCellValueFactory(cell ->
                new javafx.beans.property.SimpleStringProperty(cell.getValue().getLastStatus().getLabel()));

        anchorTable.getColumns().addAll(colNickname, colDouyinId, colWebRid, colRemark, colEnabled, colLastStatus);
        anchorTable.setItems(anchorList);
        anchorTable.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        anchorTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        ColumnWidthStore.restore(anchorTable, "anchor");
        ColumnWidthStore.bindSave(anchorTable, "anchor");
        anchorTable.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(Anchor a, boolean empty) {
                super.updateItem(a, empty);
                if (empty || a == null) {
                    setStyle("");
                } else if (!a.isEnabled()) {
                    setStyle("-fx-background-color: #f0f0f0;");
                } else {
                    setStyle("");
                }
            }
        });

        // 选中行回填表单
        anchorTable.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, sel) -> fillForm(sel));

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.setPadding(new Insets(10));
        form.add(new Label("昵称:"), 0, 0);
        form.add(tfNickname, 1, 0);
        form.add(new Label("抖音号:"), 0, 1);
        form.add(tfDouyinId, 1, 1);
        form.add(new Label("房间号:"), 0, 2);
        form.add(tfWebRid, 1, 2);
        form.add(new Label("主页URL:"), 0, 3);
        form.add(tfHomeUrl, 1, 3);
        form.add(new Label("备注:"), 0, 4);
        form.add(tfRemark, 1, 4);
        form.add(cbEnabled, 1, 5);

        Button btnAdd = new Button("新增");
        Button btnUpdate = new Button("更新");
        Button btnDelete = new Button("删除");
        Button btnClear = new Button("清空表单");
        Button btnRefresh = new Button("刷新");
        btnAdd.setOnAction(e -> addAnchor());
        btnUpdate.setOnAction(e -> updateAnchor());
        btnDelete.setOnAction(e -> deleteAnchor());
        btnClear.setOnAction(e -> clearForm());
        btnRefresh.setOnAction(e -> refreshAnchorTable());

        HBox btnBox = new HBox(10, btnAdd, btnUpdate, btnDelete, btnClear, btnRefresh);
        form.add(btnBox, 1, 6);

        VBox box = new VBox(10, anchorTable, form);
        box.setPadding(new Insets(10));
        VBox.setVgrow(anchorTable, Priority.ALWAYS);
        return box;
    }

    private void fillForm(Anchor a) {
        if (a == null) {
            return;
        }
        tfNickname.setText(a.getNickname());
        tfDouyinId.setText(a.getDouyinId());
        tfWebRid.setText(a.getWebRid() == null ? "" : a.getWebRid());
        tfHomeUrl.setText(a.getHomeUrl() == null ? "" : a.getHomeUrl());
        tfRemark.setText(a.getRemark() == null ? "" : a.getRemark());
        cbEnabled.setSelected(a.isEnabled());
    }

    private void clearForm() {
        tfNickname.clear();
        tfDouyinId.clear();
        tfWebRid.clear();
        tfHomeUrl.clear();
        tfRemark.clear();
        cbEnabled.setSelected(true);
        anchorTable.getSelectionModel().clearSelection();
    }

    private void addAnchor() {
        Anchor a = buildAnchorFromForm();
        if (a == null) {
            return;
        }
        try {
            anchorService.add(a);
            logService.info("主播管理", "新增主播: " + a.getNickname());
            refreshAnchorTable();
            clearForm();
        } catch (Exception ex) {
            showError("新增失败: " + ex.getMessage());
        }
    }

    private void updateAnchor() {
        Anchor selected = anchorTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showWarn("请先选择要更新的主播");
            return;
        }
        Anchor a = buildAnchorFromForm();
        if (a == null) {
            return;
        }
        a.setId(selected.getId());
        try {
            anchorService.update(a);
            logService.info("主播管理", "更新主播: " + a.getNickname());
            refreshAnchorTable();
            clearForm();
        } catch (Exception ex) {
            showError("更新失败: " + ex.getMessage());
        }
    }

    private void deleteAnchor() {
        Anchor selected = anchorTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showWarn("请先选择要删除的主播");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "确定删除主播「" + selected.getNickname() + "」吗？", ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("确认删除");
        confirm.setHeaderText(null);
        AppIcon.apply(confirm);
        confirm.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK) {
                try {
                    anchorService.delete(selected.getId());
                    logService.info("主播管理", "删除主播: " + selected.getNickname());
                    refreshAnchorTable();
                } catch (Exception ex) {
                    showError("删除失败: " + ex.getMessage());
                }
            }
        });
    }

    private Anchor buildAnchorFromForm() {
        String nickname = tfNickname.getText();
        String douyinId = tfDouyinId.getText();
        if (nickname == null || nickname.isBlank()) {
            showWarn("请填写主播昵称");
            return null;
        }
        if (douyinId == null || douyinId.isBlank()) {
            showWarn("请填写抖音号");
            return null;
        }
        Anchor a = new Anchor();
        a.setNickname(nickname.trim());
        a.setDouyinId(douyinId.trim());
        a.setWebRid(emptyToNull(tfWebRid.getText()));
        a.setHomeUrl(emptyToNull(tfHomeUrl.getText()));
        a.setRemark(emptyToNull(tfRemark.getText()));
        a.setEnabled(cbEnabled.isSelected());
        a.setLastStatus(LiveStatus.UNKNOWN);
        return a;
    }

    private String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * 将直播状态映射为监控状态列的展示文本。
     */
    private String statusLabel(LiveStatus status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case LIVE -> "开播中";
            case OFFLINE -> "未开播";
            case UNKNOWN -> "未知";
        };
    }

    // ==================== 监控状态 Tab ====================

    private Parent buildStatusTab() {
        TableColumn<Anchor, String> colNickname = new TableColumn<>("昵称");
        colNickname.setCellValueFactory(new PropertyValueFactory<>("nickname"));
        TableColumn<Anchor, String> colDouyinId = new TableColumn<>("抖音号");
        colDouyinId.setCellValueFactory(new PropertyValueFactory<>("douyinId"));
        TableColumn<Anchor, String> colWebRid = new TableColumn<>("房间号");
        colWebRid.setCellValueFactory(new PropertyValueFactory<>("webRid"));
        TableColumn<Anchor, String> colStatus = new TableColumn<>("开播状态");
        colStatus.setCellValueFactory(cell ->
                new javafx.beans.property.SimpleStringProperty(statusLabel(cell.getValue().getLastStatus())));
        colStatus.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else if ("开播中".equals(item)) {
                    setText(item);
                    setStyle("-fx-text-fill: #e74c3c; -fx-font-weight: bold;");
                } else if ("未知".equals(item)) {
                    setText(item);
                    setStyle("-fx-text-fill: #ff9800;");
                } else {
                    setText(item);
                    setStyle("");
                }
            }
        });
        TableColumn<Anchor, String> colLastCheck = new TableColumn<>("最近检测时间");
        colLastCheck.setCellValueFactory(cell ->
                new javafx.beans.property.SimpleStringProperty(
                        TimeUtil.format(cell.getValue().getLastCheckTime())));

        statusTable.getColumns().addAll(colStatus, colNickname, colDouyinId, colWebRid, colLastCheck);
        statusTable.setItems(statusList);
        statusTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        ColumnWidthStore.restore(statusTable, "status");
        ColumnWidthStore.bindSave(statusTable, "status");

        // 开播行高亮
        statusTable.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(Anchor a, boolean empty) {
                super.updateItem(a, empty);
                if (empty || a == null) {
                    setStyle("");
                } else if (a.getLastStatus() == LiveStatus.LIVE) {
                    setStyle("-fx-background-color: #d4edda; -fx-font-weight: bold;");
                } else if (a.getLastStatus() == LiveStatus.UNKNOWN) {
                    setStyle("-fx-background-color: #fff3cd;");
                } else {
                    setStyle("");
                }
            }
        });

        // 注册状态监听（仅更新监控状态表，主播管理表为手动刷新）
        scheduler.addStatusListener(anchors -> Platform.runLater(() -> updateStatusList(anchors)));

        Button btnRefresh = new Button("手动刷新");
        btnRefresh.setOnAction(e -> refreshStatusTable());

        VBox box = new VBox(10, btnRefresh, statusTable);
        box.setPadding(new Insets(10));
        VBox.setVgrow(statusTable, Priority.ALWAYS);
        return box;
    }

    // ==================== 日志 Tab ====================

    private Parent buildLogTab() {
        TableColumn<MonitorLog, String> colTime = new TableColumn<>("时间");
        colTime.setCellValueFactory(cell ->
                new javafx.beans.property.SimpleStringProperty(
                        TimeUtil.format(cell.getValue().getCreateTime())));
        TableColumn<MonitorLog, String> colLevel = new TableColumn<>("级别");
        colLevel.setCellValueFactory(new PropertyValueFactory<>("level"));
        TableColumn<MonitorLog, String> colSource = new TableColumn<>("来源");
        colSource.setCellValueFactory(new PropertyValueFactory<>("source"));
        TableColumn<MonitorLog, String> colMessage = new TableColumn<>("内容");
        colMessage.setCellValueFactory(new PropertyValueFactory<>("message"));

        logTable.getColumns().addAll(colTime, colLevel, colSource, colMessage);
        logTable.setItems(logList);
        logTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        ColumnWidthStore.restore(logTable, "log");
        ColumnWidthStore.bindSave(logTable, "log");

        Button btnRefresh = new Button("刷新");
        Button btnClear = new Button("清空日志");
        btnRefresh.setOnAction(e -> refreshLogs());
        btnClear.setOnAction(e -> {
            logService.clear();
            logList.clear();
        });

        HBox bar = new HBox(10, btnRefresh, btnClear);
        VBox box = new VBox(10, bar, logTable);
        box.setPadding(new Insets(10));
        VBox.setVgrow(logTable, Priority.ALWAYS);
        return box;
    }

    // ==================== 开播记录 Tab ====================

    private Parent buildSessionTab() {
        TableColumn<AnchorLiveSession, String> colStart = new TableColumn<>("开播时间");
        colStart.setCellValueFactory(cell ->
                new javafx.beans.property.SimpleStringProperty(
                        TimeUtil.format(cell.getValue().getStartTime())));
        TableColumn<AnchorLiveSession, String> colEnd = new TableColumn<>("关播时间");
        colEnd.setCellValueFactory(cell ->
                new javafx.beans.property.SimpleStringProperty(
                        TimeUtil.format(cell.getValue().getEndTime())));
        TableColumn<AnchorLiveSession, String> colDuration = new TableColumn<>("开播时长");
        colDuration.setCellValueFactory(cell ->
                new javafx.beans.property.SimpleStringProperty(
                        formatDuration(cell.getValue())));
        TableColumn<AnchorLiveSession, String> colNickname = new TableColumn<>("主播昵称");
        colNickname.setCellValueFactory(new PropertyValueFactory<>("nickname"));

        sessionTable.getColumns().addAll(colNickname, colStart, colEnd, colDuration);
        sessionTable.setItems(sessionList);
        sessionTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        ColumnWidthStore.restore(sessionTable, "session");
        ColumnWidthStore.bindSave(sessionTable, "session");

        Button btnRefresh = new Button("刷新");
        btnRefresh.setOnAction(e -> refreshSessions());

        VBox box = new VBox(10, btnRefresh, sessionTable);
        box.setPadding(new Insets(10));
        VBox.setVgrow(sessionTable, Priority.ALWAYS);
        return box;
    }

    /**
     * 格式化开播时长：已关播显示时长，直播中显示「直播中」。
     */
    private String formatDuration(AnchorLiveSession s) {
        if (s.getEndTime() == null) {
            return "直播中";
        }
        Long seconds = s.getDurationSeconds();
        if (seconds == null) {
            return "";
        }
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long sec = seconds % 60;
        if (h > 0) {
            return String.format("%d小时%d分%d秒", h, m, sec);
        }
        if (m > 0) {
            return String.format("%d分%d秒", m, sec);
        }
        return sec + "秒";
    }

    private void refreshSessions() {
        try {
            List<AnchorLiveSession> sessions = sessionDao.findRecent(200);
            // 保持选中行：刷新后仍选中同一开播记录
            TableSelectionKeeper.refresh(sessionTable, sessions, AnchorLiveSession::getId);
        } catch (Exception e) {
            log.error("刷新开播记录失败", e);
        }
    }

    // ==================== 数据刷新 ====================

    /**
     * 刷新主播管理表格（手动触发）：保留当前选中行，避免刷新后取消选中。
     */
    private void refreshAnchorTable() {
        try {
            TableSelectionKeeper.refresh(anchorTable, anchorService.findAll(), Anchor::getId);
        } catch (Exception e) {
            log.error("刷新主播列表失败", e);
        }
    }

    /**
     * 刷新监控状态表格（定时触发）。
     */
    private void refreshStatusTable() {
        try {
            List<Anchor> list = anchorService.findAll();
            if (scheduler.isRunning()) {
                updateStatusList(list);
            }
        } catch (Exception e) {
            log.error("刷新监控状态列表失败", e);
        }
    }

    /**
     * 更新监控状态表格数据，保持用户选中行不被清空。
     */
    private void updateStatusList(List<Anchor> anchors) {
        TableSelectionKeeper.refresh(statusTable, anchors, Anchor::getId);
    }

    private void refreshLogs() {
        try {
            List<MonitorLog> logs = logService.recent(200);
            // 保持选中行：新日志插入后原选中日志仍保持选中
            TableSelectionKeeper.refresh(logTable, logs, MonitorLog::getId);
        } catch (Exception e) {
            log.error("刷新日志失败", e);
        }
    }

    // ==================== 工具 ====================

    private void showError(String msg) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR, msg);
            alert.setTitle("错误");
            alert.setHeaderText(null);
            AppIcon.apply(alert);
            alert.show();
        });
    }

    private void showWarn(String msg) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING, msg);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            AppIcon.apply(alert);
            alert.show();
        });
    }

    public void shutdown() {
        if (refreshTimer != null) {
            refreshTimer.stop();
        }
        lblClock.stop();
        scheduler.shutdown();
        proxyPool.shutdown();
    }
}
