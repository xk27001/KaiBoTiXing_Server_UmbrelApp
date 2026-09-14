package com.kaibotixing.reminder;

import com.kaibotixing.util.SingleInstanceGuard;
import javafx.application.Application;

/**
 * 主播开播提醒客户端 main 入口。
 * JavaFX 的 main 类不能继承 Application，故单独提供 Launcher。
 */
public class ReminderLauncher {

    public static void main(String[] args) {
        // 统一使用 UTF-8，避免界面中文乱码
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.jnu.encoding", "UTF-8");

        // 单实例：客户端同一时刻只允许一个实例（即只允许一个主窗体）；
        // 已有实例在运行时，本进程请求其显示主窗体后直接退出
        if (!SingleInstanceGuard.tryAcquire(SingleInstanceGuard.APP_CLIENT, SingleInstanceGuard.PORT_CLIENT)) {
            return;
        }

        Application.launch(ReminderApplication.class, args);
    }
}
