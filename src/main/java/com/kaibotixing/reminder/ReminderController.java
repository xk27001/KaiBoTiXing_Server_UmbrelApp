package com.kaibotixing.reminder;

import com.kaibotixing.dao.AnchorDao;
import com.kaibotixing.dao.AnchorLiveSessionDao;
import com.kaibotixing.model.Anchor;
import com.kaibotixing.model.AnchorLiveSession;
import com.kaibotixing.model.LiveStatus;
import com.kaibotixing.service.AlertService;
import com.kaibotixing.service.AutoStartService;
import com.kaibotixing.util.AppIcon;
import com.kaibotixing.util.SystemClockLabel;
import com.kaibotixing.util.TableSelectionKeeper;
import com.kaibotixing.util.TimeUtil;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 主播开播提醒客户端控制器（单例）。
 * <p>
 * 每秒监控 anchor 表，检测主播 last_status 从非开播变为开播（开播边沿），
 * 触发置顶弹窗 + 声音提醒；主播状态表格与开播记录表格每秒刷新。
 * </p>
 */
public class ReminderController {

    private static final Logger log = LoggerFactory.getLogger(ReminderController.class);

    /** 单例实例（懒加载 + 双重检查锁） */
    private static volatile ReminderController instance;

    /**
     * 获取全局唯一控制器实例（单例），保证监控线程、界面组件仅有一份。
     */
    public static ReminderController getInstance() {
        ReminderController local = instance;
        if (local == null) {
            synchronized (ReminderController.class) {
                local = instance;
                if (local == null) {
                    local = new ReminderController();
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * 私有构造：只能通过 {@link #getInstance()} 创建。
     */
    private ReminderController() {
    }

    private final AnchorDao anchorDao = new AnchorDao();
    private final AnchorLiveSessionDao sessionDao = new AnchorLiveSessionDao();
    private final AlertService alertService = new AlertService();

    /** 开机自启动服务（独立应用名，避免与主程序注册表项冲突） */
    private final AutoStartService autoStartService = new AutoStartService("KaiBoTiXingReminder");

    /** 上次检测到的各主播状态（用于开播边沿检测） */
    private final Map<Long, LiveStatus> lastStatusMap = new HashMap<>();

    /** 每秒监控定时器 */
    private ScheduledExecutorService monitorExecutor;
    private volatile boolean running = false;
    /** 界面根节点（单例下只构建一次，避免重复创建 Tab 与监控定时器） */
    private Parent root;

    // 主播状态表格
    private final ObservableList<Anchor> anchorList = FXCollections.observableArrayList();
    private final TableView<Anchor> anchorTable = new TableView<>();
    private final Label statusLabel = new Label("监控中");

    /** 暂时勿扰开关：勾选后检测到开播不再弹窗提醒（仅记录日志），监控本身不受影响 */
    private volatile boolean doNotDisturb = false;

    /** 顶栏系统时间（红色显示，每秒刷新） */
    private final SystemClockLabel lblClock = new SystemClockLabel();

    // 开播记录表格
    private final ObservableList<AnchorLiveSession> sessionList = FXCollections.observableArrayList();
    private final TableView<AnchorLiveSession> sessionTable = new TableView<>();

    /**
     * 构建主界面：主播状态 Tab + 开播记录 Tab。
     */
    public Parent getRoot() {
        // 单例：界面只构建一次，重复调用直接返回同一根节点
        if (root != null) {
            return root;
        }

        TabPane tabPane = new TabPane();
        tabPane.getTabs().addAll(
                new Tab("主播状态", buildAnchorTab()),
                new Tab("开播记录", buildSessionTab()));

        BorderPane pane = new BorderPane(tabPane);
        pane.setTop(buildTopBar());

        // 界面构建完成后启动每秒监控
        Platform.runLater(this::startMonitoring);

        root = pane;
        return root;
    }

    private Parent buildTopBar() {
        Label title = new Label("主播开播提醒客户端");
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Label sep1 = new Label("   ");
        Label sep2 = new Label("   ");
        statusLabel.setStyle("-fx-text-fill: #27ae60;");

        // 开机自启动复选框
        javafx.scene.control.CheckBox cbAutoStart = new javafx.scene.control.CheckBox("开机自启动");
        cbAutoStart.setSelected(autoStartService.isEnabled());
        cbAutoStart.setOnAction(e -> {
            boolean want = cbAutoStart.isSelected();
            if (want && !autoStartService.isExeEnvironment()) {
                showWarn("当前为开发环境（jar 运行），开机自启动仅在打包后的 exe 版本中生效。");
                cbAutoStart.setSelected(false);
                return;
            }
            boolean ok = want ? autoStartService.enable() : autoStartService.disable();
            if (!ok) {
                showError((want ? "开启" : "关闭") + "开机自启动失败，请检查系统权限");
                cbAutoStart.setSelected(!want);
            }
        });

        // 暂时勿扰复选框：勾选后不再弹出开播提醒
        javafx.scene.control.CheckBox cbDnd = new javafx.scene.control.CheckBox("暂时勿扰");
        cbDnd.setTooltip(new javafx.scene.control.Tooltip("勾选后不再弹出开播提醒（仍会正常监控并记录日志）"));
        cbDnd.selectedProperty().addListener((obs, oldVal, newVal) -> {
            doNotDisturb = newVal;
            updateStatusLabel();
            log.info("暂时勿扰已{}", newVal ? "开启，将不再弹出开播提醒" : "关闭，恢复开播提醒");
        });

        // 右侧弹性填充，使系统时间始终靠右显示
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

        javafx.scene.layout.HBox bar = new javafx.scene.layout.HBox(10, title, sep1, statusLabel, sep2,
                cbAutoStart, cbDnd, spacer, lblClock);
        bar.setPadding(new Insets(10));
        bar.setStyle("-fx-background-color: #f4f4f4; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return bar;
    }

    /**
     * 刷新顶栏状态文案：暂时勿扰开启时以醒目橙色提示，关闭时恢复绿色「监控中」。
     */
    private void updateStatusLabel() {
        if (doNotDisturb) {
            statusLabel.setText("监控中（暂时勿扰）");
            statusLabel.setStyle("-fx-text-fill: #e67e22; -fx-font-weight: bold;");
        } else {
            statusLabel.setText("监控中");
            statusLabel.setStyle("-fx-text-fill: #27ae60;");
        }
    }

    private void showWarn(String message) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING);
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.setContentText(message);
        AppIcon.apply(alert);
        alert.showAndWait();
    }

    private void showError(String message) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
        alert.setTitle("错误");
        alert.setHeaderText(null);
        alert.setContentText(message);
        AppIcon.apply(alert);
        alert.showAndWait();
    }

    /**
     * 主播状态表格：显示所有主播的实时开播状态，每秒刷新。
     */
    private Parent buildAnchorTab() {
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

        anchorTable.getColumns().addAll(colStatus, colNickname, colDouyinId, colWebRid, colLastCheck);
        anchorTable.setItems(anchorList);
        anchorTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        VBox box = new VBox(10, anchorTable);
        box.setPadding(new Insets(10));
        VBox.setVgrow(anchorTable, javafx.scene.layout.Priority.ALWAYS);
        return box;
    }

    /**
     * 开播记录 Tab：复用 anchor_live_session 表数据，展示主播开播/关播/时长。
     */
    private Parent buildSessionTab() {
        TableColumn<AnchorLiveSession, String> colNickname = new TableColumn<>("主播昵称");
        colNickname.setCellValueFactory(new PropertyValueFactory<>("nickname"));
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

        sessionTable.getColumns().addAll(colNickname, colStart, colEnd, colDuration);
        sessionTable.setItems(sessionList);
        sessionTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        VBox box = new VBox(10, sessionTable);
        box.setPadding(new Insets(10));
        VBox.setVgrow(sessionTable, javafx.scene.layout.Priority.ALWAYS);
        return box;
    }

    /**
     * 启动每秒监控。
     */
    public void startMonitoring() {
        if (running) {
            return;
        }
        running = true;
        monitorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reminder-monitor");
            t.setDaemon(true);
            return t;
        });
        monitorExecutor.scheduleAtFixedRate(this::monitorOnce, 0, 1, TimeUnit.SECONDS);
        log.info("主播开播提醒监控已启动，每秒监控一次");
    }

    /**
     * 每秒执行一次：读 anchor 表、检测开播边沿、刷新表格。
     */
    private void monitorOnce() {
        try {
            List<Anchor> anchors = anchorDao.findAll();
            if (anchors.isEmpty()) {
                return;
            }

            // 首次启动：初始化状态 map，不触发提醒（避免误报）
            boolean firstRun = lastStatusMap.isEmpty();

            for (Anchor anchor : anchors) {
                LiveStatus current = anchor.getLastStatus();
                LiveStatus previous = lastStatusMap.get(anchor.getId());

                if (current == null) {
                    lastStatusMap.put(anchor.getId(), LiveStatus.UNKNOWN);
                    continue;
                }

                // 开播边沿：当前为开播且（上次非开播，或首次看到该主播）
                if (current == LiveStatus.LIVE && (previous == null || previous != LiveStatus.LIVE)) {
                    lastStatusMap.put(anchor.getId(), current);
                    if (firstRun) {
                        // 首次运行时已处于开播状态，不提醒（避免启动误报）
                        log.info("启动时主播「{}」已在直播，不重复提醒", anchor.getNickname());
                        continue;
                    }
                    if (doNotDisturb) {
                        // 暂时勿扰：只记录日志，不弹窗、不响铃
                        log.info("检测到主播「{}」开播，当前为暂时勿扰，跳过提醒", anchor.getNickname());
                    } else {
                        log.info("检测到主播「{}」开播，触发提醒", anchor.getNickname());
                        alertService.alert(anchor.getNickname(), anchor.getWebRid());
                    }
                } else {
                    lastStatusMap.put(anchor.getId(), current);
                }
            }

            // 刷新表格（FX 线程）：按开播状态排序（开播中 → 未开播 → 未知）
            Platform.runLater(() -> {
                List<Anchor> sorted = new java.util.ArrayList<>(anchors);
                sorted.sort(java.util.Comparator.comparingInt(a -> statusOrder(a.getLastStatus())));
                // 保持选中行：每秒刷新不再清空用户选中
                TableSelectionKeeper.refresh(anchorTable, sorted, Anchor::getId);
                refreshSessions();
            });
        } catch (Exception e) {
            log.warn("监控一次异常: {}", e.getMessage());
        }
    }

    /**
     * 开播状态排序值：开播中=0，未开播=1，未知=2。
     */
    private int statusOrder(LiveStatus status) {
        if (status == null) {
            return 2;
        }
        return switch (status) {
            case LIVE -> 0;
            case OFFLINE -> 1;
            case UNKNOWN -> 2;
        };
    }

    private void refreshSessions() {
        try {
            List<AnchorLiveSession> sessions = sessionDao.findRecent(200);
            // 保持选中行：刷新后仍选中同一开播记录
            TableSelectionKeeper.refresh(sessionTable, sessions, AnchorLiveSession::getId);
        } catch (SQLException e) {
            log.warn("刷新开播记录失败: {}", e.getMessage());
        }
    }

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

    /**
     * 停止监控并释放资源。
     */
    public void shutdown() {
        running = false;
        lblClock.stop();
        if (monitorExecutor != null) {
            monitorExecutor.shutdownNow();
        }
    }
}
