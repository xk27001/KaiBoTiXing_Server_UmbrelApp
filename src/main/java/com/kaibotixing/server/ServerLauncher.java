package com.kaibotixing.server;

import com.kaibotixing.util.ConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * KaiBoTiXing 无界面服务端入口，供 Docker/Umbrel 使用。
 */
public final class ServerLauncher {

    private static final Logger log = LoggerFactory.getLogger(ServerLauncher.class);

    private ServerLauncher() {
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.jnu.encoding", "UTF-8");

        ServerRuntime runtime = null;
        WebServer webServer = null;
        CountDownLatch shutdownLatch = new CountDownLatch(1);
        AtomicBoolean closing = new AtomicBoolean(false);

        try {
            runtime = new ServerRuntime();
            String listenAddress = ConfigUtil.get("server.listen", "0.0.0.0");
            int port = ConfigUtil.getInt("server.port", 8080);
            webServer = new WebServer(runtime, listenAddress, port);

            final ServerRuntime runningRuntime = runtime;
            final WebServer runningWebServer = webServer;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (!closing.compareAndSet(false, true)) {
                    return;
                }
                log.info("收到停止信号，正在关闭服务...");
                runningWebServer.close();
                runningRuntime.close();
                shutdownLatch.countDown();
            }, "server-shutdown-hook"));

            webServer.start();
            runtime.start();
            log.info("服务已就绪，按 Ctrl+C 或发送 SIGTERM 停止");
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("服务端启动失败", e);
            if (webServer != null) {
                webServer.close();
            }
            if (runtime != null) {
                runtime.close();
            }
            System.exit(1);
        }
    }
}
