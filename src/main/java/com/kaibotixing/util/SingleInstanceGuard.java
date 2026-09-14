package com.kaibotixing.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 单实例守卫：保证「客户端」与「服务端」各自在同一时刻只运行一个实例，
 * 即只有一个主窗体，避免重复双击图标出现多个主窗体。
 * <p>
 * 实现方式：
 * <ol>
 *   <li>用 {@link FileLock 文件锁}（{@code %TEMP%/KaiBoTiXing-<appId>.lock}）做权威判定：
 *       拿不到锁说明已有实例在运行，本次启动直接退出；</li>
 *   <li>已有实例时，通过回环地址的约定端口向它发送 {@code SHOW} 指令，
 *       让它在 FX 线程上显示并置顶自己的主窗体（开机自启动常驻托盘场景尤其有用）；</li>
 *   <li>首个实例在拿到锁后启动守护线程监听该端口，接收第二实例的激活请求。</li>
 * </ol>
 * 端口被无关程序占用时不会误判（是否已有实例只以文件锁为准），只是失去自动激活能力，不影响单实例效果。
 * </p>
 */
public final class SingleInstanceGuard {

    private static final Logger log = LoggerFactory.getLogger(SingleInstanceGuard.class);

    /** 「显示主窗体」指令 */
    private static final String SHOW_CMD = "SHOW";

    /** 服务端（抖音主播开播监控）应用标识 */
    public static final String APP_SERVER = "server";
    /** 服务端单实例通信端口（仅回环地址） */
    public static final int PORT_SERVER = 47731;

    /** 客户端（主播开播提醒）应用标识 */
    public static final String APP_CLIENT = "client";
    /** 客户端单实例通信端口（仅回环地址） */
    public static final int PORT_CLIENT = 47732;

    /** 锁文件句柄与锁（进程内唯一） */
    private static RandomAccessFile lockFile;
    private static FileLock fileLock;

    private SingleInstanceGuard() {
    }

    /**
     * 尝试成为唯一实例。
     *
     * @param appId 应用标识（用于锁文件名，客户端/服务端必须不同）
     * @param port  该应用的激活指令端口
     * @return true 表示本进程是唯一实例，可以继续启动；
     *         false 表示已有实例在运行（已请求其显示主窗体），本次启动应立即退出
     */
    public static synchronized boolean tryAcquire(String appId, int port) {
        Path lockPath = lockPath(appId);
        try {
            // 注意：不能用 try-with-resources，锁需要在进程整个生命周期内保持
            RandomAccessFile raf = new RandomAccessFile(lockPath.toFile(), "rw");
            FileChannel channel = raf.getChannel();
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                // 同一 JVM 已持有该锁 → 本进程已存在一个实例
                lock = null;
            }
            if (lock == null) {
                closeQuietly(channel, raf);
                log.info("检测到已有实例在运行（锁文件 {}），本次启动退出", lockPath);
                notifyExistingInstance(port);
                return false;
            }
            lockFile = raf;
            fileLock = lock;
            log.info("单实例锁已获取：{}", lockPath);
            return true;
        } catch (Exception e) {
            // 锁文件不可用（如临时目录只读）时不阻断启动，退化为允许多实例
            log.warn("单实例锁初始化失败，本次启动不启用单实例保护: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 启动守护线程，接收后启动实例发来的「显示主窗体」请求。
     *
     * @param port            该应用的激活指令端口，需与 {@link #tryAcquire} 一致
     * @param onShowRequest   收到请求后的回调（实现方需自行切回 FX 线程并显示/置顶主窗体）
     */
    public static void listenShowRequests(int port, Runnable onShowRequest) {
        Thread listener = new Thread(() -> {
            try (ServerSocket server = new ServerSocket()) {
                server.setReuseAddress(true);
                // 仅绑定回环地址，不对外暴露
                server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 4);
                log.info("单实例监听已启动（端口 {}），重复启动将自动激活主窗体", port);
                while (!Thread.currentThread().isInterrupted()) {
                    try (Socket socket = server.accept()) {
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        String command = reader.readLine();
                        if (SHOW_CMD.equals(command)) {
                            log.info("收到第二实例的显示主窗体请求，激活主窗体");
                            onShowRequest.run();
                        }
                    } catch (Exception e) {
                        log.debug("处理第二实例请求失败: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("单实例监听启动失败（端口 {} 不可用），重复启动时无法自动激活主窗体: {}",
                        port, e.getMessage());
            }
        }, "single-instance-listener");
        listener.setDaemon(true);
        listener.start();
    }

    /**
     * 释放单实例锁（退出时调用，非必须：进程结束后操作系统会自动释放）。
     */
    public static synchronized void release() {
        try {
            if (fileLock != null && fileLock.isValid()) {
                fileLock.release();
            }
        } catch (Exception e) {
            log.debug("释放单实例文件锁失败: {}", e.getMessage());
        } finally {
            fileLock = null;
            if (lockFile != null) {
                try {
                    lockFile.close();
                } catch (Exception e) {
                    log.debug("关闭单实例锁文件失败: {}", e.getMessage());
                }
                lockFile = null;
            }
        }
    }

    private static Path lockPath(String appId) {
        String tmpDir = System.getProperty("java.io.tmpdir", ".");
        return Paths.get(tmpDir, "KaiBoTiXing-" + appId + ".lock");
    }

    /**
     * 通知已在运行的实例显示主窗体；连接带重试（覆盖首实例尚未启动监听的窗口期）。
     */
    private static void notifyExistingInstance(int port) {
        boolean sent = false;
        for (int i = 0; i < 30 && !sent; i++) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 300);
                try (Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
                    writer.write(SHOW_CMD);
                    writer.write('\n');
                    writer.flush();
                }
                sent = true;
            } catch (Exception e) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (sent) {
            log.info("已请求原有实例显示主窗体，本次启动退出");
        } else {
            log.warn("已有实例在运行，但未能激活其主窗体（端口 {} 无响应）", port);
            showAlreadyRunningTip();
        }
    }

    /**
     * 激活失败时的兜底提示（第二实例未启动 JavaFX，使用 Swing 弹窗提示）。
     */
    private static void showAlreadyRunningTip() {
        try {
            javax.swing.JOptionPane.showMessageDialog(null,
                    "该程序已在运行中，请勿重复启动。\n若未看到窗口，请在系统托盘中找到程序图标，选择「显示主窗口」。",
                    "提示", javax.swing.JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            log.debug("兜底提示弹窗失败: {}", e.getMessage());
        }
    }

    private static void closeQuietly(FileChannel channel, RandomAccessFile raf) {
        try {
            channel.close();
        } catch (Exception ignored) {
            // 忽略
        }
        try {
            raf.close();
        } catch (Exception ignored) {
            // 忽略
        }
    }
}
