package com.kaibotixing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 开机自启动服务：通过 Windows 注册表 HKCU\...\Run 键实现。
 * <p>
 * 写入的键值为当前程序的可执行文件路径（exe 或 jar），并附带 --auto-start 参数，
 * 使软件随系统登录自启动后自动开启监控。
 * </p>
 */
public class AutoStartService {

    private static final Logger log = LoggerFactory.getLogger(AutoStartService.class);

    private static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    /** 应用名（用于注册表键名与 exe 文件名识别），默认主程序名 */
    private final String appName;

    public AutoStartService() {
        this("KaiBoTiXing");
    }

    /**
     * @param appName 应用名：用作注册表键名，并用于识别 exe 文件名（{appName}.exe）
     */
    public AutoStartService(String appName) {
        this.appName = appName == null || appName.isBlank() ? "KaiBoTiXing" : appName;
    }

    private String valueName() {
        return appName;
    }

    /**
     * 判断当前是否以打包后的 exe 运行（jpackage 会写入该系统属性）。
     */
    public boolean isExeEnvironment() {
        return System.getProperty("jpackage.app-version") != null;
    }

    /**
     * 计算当前程序的可执行路径：exe 运行则返回 exe 路径，否则返回 jar 所在 java 命令。
     */
    private String executablePath() {
        // exe 环境：java.home 的上级是 exe 安装目录
        String appVersion = System.getProperty("jpackage.app-version");
        if (appVersion != null) {
            String exeDir = System.getProperty("java.home");
            // jpackage 中 java.home = {app}/runtime，exe 在 {app} 目录下
            File appDir = new File(exeDir).getParentFile();
            if (appDir != null) {
                // 优先：jpackage 默认 exe 文件名与目录名一致（{appDir}/{appDir.getName()}.exe）
                File exe = new File(appDir, appDir.getName() + ".exe");
                if (exe.exists()) {
                    return exe.getAbsolutePath();
                }
                // 兜底：查找 appDir 下第一个 exe（用户可能重命名了目录）
                File[] exes = appDir.listFiles((dir, name) ->
                        name.toLowerCase().endsWith(".exe"));
                if (exes != null && exes.length > 0) {
                    return exes[0].getAbsolutePath();
                }
            }
        }
        // jar 环境：返回 java 可执行文件 + -jar 当前 jar 路径
        String javaHome = System.getProperty("java.home");
        String javaExe = javaHome + File.separator + "bin" + File.separator + "java.exe";
        String jarPath = System.getProperty("java.class.path");
        return "\"" + javaExe + "\" -jar \"" + jarPath + "\"";
    }

    /**
     * 开启开机自启动。
     *
     * @return 是否成功
     */
    public boolean enable() {
        try {
            // 使用 ProcessBuilder 直接传递参数，避免 cmd 对含空格/引号路径的解析错误
            Process p = new ProcessBuilder("reg", "add", RUN_KEY,
                    "/v", valueName(), "/t", "REG_SZ",
                    "/d", executablePath() + " --auto-start", "/f")
                    .redirectErrorStream(true).start();
            return handleExit(p);
        } catch (Exception e) {
            log.warn("写入开机自启动注册表失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 关闭开机自启动。
     *
     * @return 是否成功
     */
    public boolean disable() {
        try {
            Process p = new ProcessBuilder("reg", "delete", RUN_KEY,
                    "/v", valueName(), "/f")
                    .redirectErrorStream(true).start();
            return handleExit(p);
        } catch (Exception e) {
            log.warn("删除开机自启动注册表失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 查询当前是否已开启开机自启动。
     */
    public boolean isEnabled() {
        try {
            Process p = new ProcessBuilder("reg", "query", RUN_KEY, "/v", valueName())
                    .redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            return output.contains(valueName());
        } catch (Exception e) {
            log.warn("查询开机自启动状态失败: {}", e.getMessage());
            return false;
        }
    }

    private boolean handleExit(Process p) throws IOException, InterruptedException {
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        if (exit != 0) {
            log.warn("reg 命令执行失败(exit={}): {}", exit, output.trim());
            return false;
        }
        return true;
    }
}
