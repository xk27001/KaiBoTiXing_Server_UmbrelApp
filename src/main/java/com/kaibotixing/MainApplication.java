package com.kaibotixing;

import com.kaibotixing.dao.DataSourceManager;
import com.kaibotixing.ui.MainController;
import com.kaibotixing.util.AppIcon;
import com.kaibotixing.util.SingleInstanceGuard;
import dorkbox.systemTray.Menu;
import dorkbox.systemTray.MenuItem;
import dorkbox.systemTray.SystemTray;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JavaFX 应用主类（服务端：主播开播监控）。
 * <p>
 * 单例：JavaFX 要求 Application 子类必须提供 public 无参构造，无法使用私有构造的单例写法，
 * 因此采用「登记式单例」——实例在构造时登记，全局通过 {@link #getInstance()} 获取。
 * </p>
 * <p>
 * 支持最小化到系统托盘（使用 dorkbox SystemTray 库，解决 AWT 托盘中文乱码与事件失效问题）：
 * 点击窗口关闭按钮不退出，仅隐藏窗口驻留托盘；开机自启动（--auto-start）场景下启动后直接
 * 最小化到托盘。主窗口与所有弹窗均使用统一的 {@link AppIcon 应用图标}。
 * </p>
 */
public class MainApplication extends Application {

    private static final Logger log = LoggerFactory.getLogger(MainApplication.class);

    /** 单例实例（由 JavaFX 反射创建时登记） */
    private static volatile MainApplication instance;

    private MainController controller;
    private SystemTray systemTray;
    private Stage primaryStage;
    private boolean autoStart;

    /**
     * 单例构造（登记式）：JavaFX 通过反射调用该构造创建唯一实例，此处登记为单例。
     */
    public MainApplication() {
        instance = this;
    }

    /**
     * 获取应用单例实例。
     */
    public static MainApplication getInstance() {
        return instance;
    }

    /**
     * 获取主窗口（应用启动后可用）。
     */
    public Stage getPrimaryStage() {
        return primaryStage;
    }

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        // 初始化数据库连接
        try {
            DataSourceManager.getDataSource();
        } catch (Exception e) {
            log.error("数据库初始化失败", e);
        }

        // 解析 --auto-start 参数，自启动场景自动开启监控
        autoStart = getParameters().getUnnamed().contains("--auto-start");
        // 控制器为单例：全局唯一实例，避免重复启动代理池/调度器
        controller = MainController.getInstance();
        controller.setAutoStart(autoStart);
        Scene scene = new Scene(controller.getRoot(), 1100, 700);
        scene.getStylesheets().add(
                getClass().getClassLoader().getResource("css/app.css").toExternalForm());

        // 关键：窗口隐藏到托盘后仍保持 JavaFX 运行，否则 FX 线程退出导致
        // Platform.runLater 不执行，「显示主窗口/退出」托盘菜单功能失效
        Platform.setImplicitExit(false);

        primaryStage.setTitle("抖音主播开播监控");
        primaryStage.setScene(scene);
        // 主窗口图标
        AppIcon.apply(primaryStage);

        // 点击关闭按钮：最小化到托盘，不退出
        primaryStage.setOnCloseRequest(e -> {
            e.consume();
            primaryStage.hide();
        });

        // 初始化系统托盘（dorkbox）
        initTray(primaryStage);

        // 单实例：响应第二实例的「显示主窗体」请求，保证全局只有一个主窗体
        SingleInstanceGuard.listenShowRequests(SingleInstanceGuard.PORT_SERVER,
                () -> Platform.runLater(() -> showStage(primaryStage)));

        if (autoStart) {
            // 自启动场景：不显示主窗口，直接最小化到托盘
            primaryStage.hide();
            showTrayNotice("抖音主播开播监控已启动，监控正在后台运行");
        } else {
            primaryStage.show();
        }
    }

    /**
     * 初始化系统托盘（dorkbox SystemTray），含图标、右键菜单（显示主窗口 / 退出）。
     */
    private void initTray(Stage primaryStage) {
        try {
            // 纯 JavaFX 应用中强制使用 Swing 托盘类型：其菜单事件由 Swing EDT 派发，
            // 能可靠触发（默认 AWT 类型在无 AWT 事件循环的 JavaFX 应用中事件不派发）
            SystemTray.FORCE_TRAY_TYPE = SystemTray.TrayType.Swing;
            systemTray = SystemTray.get();
            // 托盘图标与窗口图标保持一致
            systemTray.setImage(AppIcon.awtIcon(16));

            Menu menu = systemTray.getMenu();

            MenuItem showItem = new MenuItem("显示主窗口",
                    e -> {
                        log.info("托盘菜单：显示主窗口被点击");
                        Platform.runLater(() -> showStage(primaryStage));
                    });
            MenuItem exitItem = new MenuItem("退出",
                    e -> {
                        log.info("托盘菜单：退出被点击");
                        Platform.runLater(() -> exitApplication());
                    });

            menu.add(showItem);
            menu.add(exitItem);

            log.info("系统托盘初始化完成，类型: {}", systemTray.getType());
        } catch (Exception e) {
            log.warn("初始化系统托盘失败，关闭按钮将直接退出: {}", e.getMessage());
        }
    }

    private void showStage(Stage stage) {
        if (stage.isIconified()) {
            stage.setIconified(false);
        }
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    /**
     * 真正退出：释放资源、移除托盘、退出 JavaFX。
     */
    private void exitApplication() {
        if (controller != null) {
            controller.shutdown();
        }
        if (systemTray != null) {
            try {
                systemTray.remove();
            } catch (Exception e) {
                log.warn("移除托盘失败: {}", e.getMessage());
            }
        }
        // 释放单实例锁（进程退出时操作系统也会释放，这里显式释放便于调试）
        SingleInstanceGuard.release();
        Platform.exit();
        System.exit(0);
    }

    /**
     * 托盘气泡提示。
     */
    private void showTrayNotice(String message) {
        // dorkbox 托盘图标显示后，通过日志提示（dorkbox 无内置气泡 API，简单处理）
        log.info(message);
    }

    @Override
    public void stop() {
        SingleInstanceGuard.release();
        DataSourceManager.close();
    }
}
