package com.kaibotixing;

import com.kaibotixing.util.SingleInstanceGuard;
import javafx.application.Application;

/**
 * 程序入口。
 * 由于 JavaFX 模块化限制，main 方法所在类不能继承 Application，
 * 需通过独立的 Launcher 调用 Application.launch。
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        // 统一使用 UTF-8，避免系统托盘等 AWT 原生组件在中文 Windows 上显示乱码
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.jnu.encoding", "UTF-8");

        // 单实例：服务端同一时刻只允许一个实例（即只允许一个主窗体）；
        // 已有实例在运行时，本进程请求其显示主窗体后直接退出
        if (!SingleInstanceGuard.tryAcquire(SingleInstanceGuard.APP_SERVER, SingleInstanceGuard.PORT_SERVER)) {
            return;
        }

        Application.launch(MainApplication.class, args);
    }
}

