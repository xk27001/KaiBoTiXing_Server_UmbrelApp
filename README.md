# 抖音主播开播监控软件

基于 JavaFX 的抖音主播开播监控桌面软件，采用多线程每 30 秒并发爬取主播直播状态，数据持久化到 MySQL，支持主播管理、实时状态展示、日志查看、启停控制，并在检测到开播时弹窗 + 声音提醒。

## 技术栈

- Java 25（OpenJDK 25.0.3 LTS）
- JavaFX 23.0.2（桌面 GUI）+ JDK HttpServer（Umbrel Web 控制台）
- MySQL（阿里云 RDS）+ HikariCP 连接池 + 纯 JDBC
- Jackson（JSON 解析）、SLF4J + Logback（日志）
- `ScheduledExecutorService` + 线程池（多线程并发爬取）
- Maven + shade + jlink + jpackage（构建与打包）

## 功能特性

| 模块 | 说明 |
|------|------|
| 主播管理 | 增删改查主播（昵称、抖音号、房间号 web_rid、主页 URL、备注、是否启用） |
| 监控状态 | 实时展示各主播开播状态（开播/未开播/未知），开播行高亮 |
| 日志查看 | 查看监控日志（写入数据库 + 文件），支持刷新与清空 |
| 启停控制 | 一键启动/停止监控，状态持久化 |
| 开播提醒 | 检测到主播由未开播变为开播时，弹出通知窗口并播放提示音 |
| 反爬应对 | 每次请求随机 User-Agent；免费代理池（多源拉取→并发验证→随机轮换，503/失败自动换代理重试） |
| 代理池详情 | 点击顶部「代理池详情」按钮打开独立窗口，实时查看拉取/验证处理过程、验证进度与可用代理列表，支持手动立即刷新 |
| 数据持久化 | 主播、配置、爬取结果、日志全部存入 MySQL |

## 数据库配置

数据库连接信息位于 `src/main/resources/config/db.properties`（已加入 `.gitignore`，**请勿把真实账号密码提交到仓库**）。
该文件需自行创建，字段如下（占位符仅示意，请替换为实际值）：

```properties
db.host=<你的 MySQL 地址，例如 rm-xxxxxxxxxxxx.mysql.rds.aliyuncs.com>
db.port=3306
db.database=kai_bo_ti_xing
db.username=<数据库用户名>
db.password=<数据库密码>
```

程序首次启动时会自动创建数据库（若不存在）和四张表：

- `anchor` — 主播表
- `monitor_config` — 监控配置表（爬取间隔、线程数等）
- `monitor_record` — 爬取结果历史表
- `monitor_log` — 业务日志表

## 构建与运行

### 开发运行

```bash
mvn clean package -DskipTests          # 编译打包
mvn javafx:run                         # 通过 JavaFX 插件运行
# 或直接运行 fat jar
java -jar target/KaiBoTiXing-1.0.0-fat.jar
```

### 运行测试

```bash
mvn test
```

### 打包 Windows exe

一条命令同时打包「服务端」与「客户端」两个免安装绿色版：

```powershell
powershell -ExecutionPolicy Bypass -File package-exe.ps1             # 服务端 + 客户端
powershell -ExecutionPolicy Bypass -File package-exe.ps1 -App server # 仅服务端
powershell -ExecutionPolicy Bypass -File package-exe.ps1 -App client # 仅客户端
```

打包完成后：

- `dist/KaiBoTiXing/KaiBoTiXing.exe` —— 服务端（抖音主播开播监控）
- `dist/Reminder/Reminder.exe` —— 客户端（主播开播提醒）

两个目录均为免安装绿色版，双击对应 exe 运行（内置 JRE，无需目标机器预装 Java）。两个程序各自单实例运行（重复启动只会激活已有主窗体，不会出现第二个窗口），窗口、弹窗与托盘图标统一。

## Umbrel 部署（社区应用商店）

> 从发布镜像、创建 GitHub 镜像仓库、添加社区商店到首次使用和排错，请直接按照 [`UMBREL_INSTALL.md`](UMBREL_INSTALL.md) 操作。

仓库已转换为 Umbrel 社区应用商店结构，并新增无界面 Web 服务端：

```text
umbrel-app-store.yml                 # Umbrel 社区应用商店清单
kaibotixing-server/
├── umbrel-app.yml                   # 应用商店展示信息
├── docker-compose.yml               # Web 服务端 + MySQL 8
├── Dockerfile                       # Linux/Umbrel 镜像
└── icon.svg                         # 应用图标
.github/workflows/umbrel-image.yml   # 构建并推送 GHCR 镜像
```

服务端通过 `com.kaibotixing.server.ServerLauncher` 启动，不再依赖桌面环境。Web 控制台提供监控启停、主播管理、实时状态、开播记录、日志和代理池状态。

### 1. 发布镜像

Umbrel 安装应用时会拉取预构建镜像，因此首次安装前必须先发布 `kai-bo-ti-xing` 镜像。默认镜像名为：

```text
ghcr.io/xk27001/kai-bo-ti-xing:1.0.0-umbrel
```

当前已配置为 GitHub 用户 `xk27001`。如需使用其他用户名，请同步修改 `kaibotixing-server/docker-compose.yml` 中的 `image`。推送到 GitHub 后，可手动运行仓库的 **Build Umbrel image** workflow，或在能访问 Docker 的机器上执行：

```bash
docker build -f kaibotixing-server/Dockerfile -t ghcr.io/<owner>/kai-bo-ti-xing:1.0.0-umbrel .
docker push ghcr.io/<owner>/kai-bo-ti-xing:1.0.0-umbrel
```

> 首次推送后，请在 GitHub 的 Packages 页面把 `kai-bo-ti-xing` 容器包可见性设为 **Public**，否则 Umbrel 无法匿名拉取镜像。

### 2. Gitee 与 GitHub 的关系

Gitee **不能直接替代** Umbrel 社区应用商店所需的 GitHub 仓库地址。umbrelOS 的 **Community App Stores** 通过 GitHub 仓库和 GitHub API 拉取商店清单，因此 Gitee 地址不能被直接添加为社区商店。

推荐的组合方式：

- **Gitee**：作为主源码仓库，保存完整 Java 项目、文档和 Dockerfile。
- **GitHub**：建立一个公开镜像仓库，至少同步 `umbrel-app-store.yml` 和 `kaibotixing-server/` 清单目录，只用于 Umbrel 安装。
- **镜像仓库**：可继续使用 GHCR；如果不想依赖 GitHub，也可以把镜像发布到 Docker Hub，然后修改 Compose 中的 `image`。
- **仅使用 Gitee**：仍可通过 SSH 在 Umbrel 主机手动运行 Docker Compose，但不会出现在 umbrelOS 应用商店中，也没有一键安装和升级集成。

### 3. 添加社区应用商店

请在 umbrelOS 的 **App Store → Community App Stores** 中添加 GitHub 镜像仓库地址：

```text
https://github.com/xk27001/KaiBoTiXing_Server_UmbrelApp
```

添加成功后，在商店中安装「开播监控」。Umbrel 会自动创建 MySQL 8、持久化数据库和日志，并通过 umbrelOS 登录保护 Web 控制台。

### 4. 服务端配置

容器部署无需创建 `db.properties`，可直接使用 `KBTX_*` 环境变量覆盖配置，例如：

| 环境变量 | 说明 | Umbrel 默认值 |
|---|---|---|
| `KBTX_DB_HOST` | MySQL 8 主机 | `kaibotixing-server_db_1` |
| `KBTX_DB_DATABASE` | 数据库名 | `kai_bo_ti_xing` |
| `KBTX_DB_USERNAME` | 数据库用户 | `kaibotixing` |
| `KBTX_DB_PASSWORD` | 数据库密码 | `$APP_PASSWORD` |
| `KBTX_SERVER_PORT` | Web 服务端口 | `8080` |
| `KBTX_SERVER_MONITOR_AUTO_START` | 启动时自动开启监控 | `true` |
| `KBTX_CRAWLER_PROXY_ENABLED` | 启用免费代理池 | `true` |

本地直接运行无界面服务端：

```bash
java -cp target/KaiBoTiXing-1.0.0-fat.jar com.kaibotixing.server.ServerLauncher
```

## 项目结构

```
src/main/java/com/kaibotixing/
├── Launcher.java              # main 入口（调用 Application.launch）
├── MainApplication.java       # JavaFX Application
├── model/                     # 实体类（Anchor、MonitorConfig、MonitorRecord、MonitorLog、LiveStatus）
├── dao/                       # 数据访问层（DataSourceManager + 各 DAO）
├── service/                   # 业务层（AnchorService、LogService、AlertService）
├── crawler/                   # 爬取器（DouyinCrawler 接口 + DouyinWebCrawler 实现 + UserAgentProvider + ProxyPoolService 代理池）
├── scheduler/                 # 监控调度器（MonitorScheduler）
├── util/                      # 工具类
├── ui/                        # JavaFX 桌面界面控制器（MainController）
└── server/                    # Umbrel 无界面 Web 服务端（ServerLauncher + WebServer）
```

## 使用说明

1. 启动软件，在「主播管理」Tab 添加需要监控的主播（至少填写昵称和抖音号，房间号 web_rid 可从直播间链接获取，如 `https://live.douyin.com/918650447703` 中的数字）。
2. 点击顶部「启动监控」按钮，软件每 30 秒并发爬取所有启用主播的直播状态。
3. 在「监控状态」Tab 查看实时开播状态，开播的主播会高亮显示并触发弹窗 + 声音提醒。
4. 在「日志查看」Tab 查看监控过程与爬取结果日志。

## 反爬应对与代理池配置

爬取器通过随机 User-Agent + 免费代理池降低被抖音反爬拦截（HTTP 503）的概率，全部配置位于 `src/main/resources/config/db.properties`：

```properties
# 自定义 UA（可选，追加到内置浏览器 UA 池，多个以 | 分隔）
crawler.user.agents=
# 代理池总开关
crawler.proxy.enabled=true
# 免费代理数据源（逗号分隔多个完整 URL，每行一个 ip:port 文本格式）。
# 留空则使用内置的 3 个 GitHub 公共代理列表，并自动尝试「直连 + 镜像加速 + jsDelivr CDN」
crawler.proxy.sources=
# GitHub raw 加速镜像前缀（逗号分隔；留空使用内置镜像，2026-08 实测可用）
crawler.proxy.mirror.prefixes=
# 代理池刷新间隔（分钟）、验证超时（秒）、可用数量上限、验证并发数、验证采样倍数
crawler.proxy.refresh.minutes=30
crawler.proxy.validate.timeout.seconds=4
crawler.proxy.max.count=50
crawler.proxy.validate.parallelism=40
crawler.proxy.validate.sample.factor=5
# 代理连通性验证目标 URL（需国内可达，自动补充 http:// 版备选）
crawler.proxy.validate.url=https://www.baidu.com
# 503/403/429 或连接失败时换代理重试次数
crawler.retry.count=3
```

工作原理：

1. 程序启动后后台立即拉取一次代理源，此后按 `refresh.minutes` 周期刷新；拉取、连通性验证均在独立后台线程执行，不阻塞爬取。
2. **国内网络兼容**：数据源默认托管在 `raw.githubusercontent.com`（国内无法直连），拉取时对每个源并行尝试「原始 URL + 多个国内 GitHub 加速镜像（内置 4 个）+ jsDelivr CDN」，任一成功即可。
3. 代理经并发连通性验证（默认验证 `https://www.baidu.com`，并自动以同主机 `http://` 版兜底，兼容仅支持 HTTP 的纯代理）后进入可用池，每次爬取请求随机轮换一个代理。
4. 请求收到 503/403/429 或连接异常时，当前代理被剔除并换代理重试（默认最多 3 次）。
5. 代理池不可用/为空时自动退化为直连，保证监控功能不中断；顶部工具栏会实时显示「代理池：可用 N · 刷新 HH:mm:ss」。
6. 点击顶部工具栏「代理池详情」按钮可打开独立窗口，实时查看代理池处理过程（各数据源拉取成功/失败、候选总数、验证进度 N/M、刷新完成结果、代理被剔除记录）与可用代理列表；窗口中可手动「立即刷新」（自动拉取并重新验证，验证约数十秒内完成），无需等待 30 分钟定时刷新。

> 若内置镜像域名失效（镜像服务商变动频繁），可自行更换 `crawler.proxy.mirror.prefixes`，或在 `crawler.proxy.sources` 直接填写镜像加速后的完整 URL（如 `https://gh-proxy.com/https://raw.githubusercontent.com/...`）。

> 免费代理质量不稳定属正常现象；若长期获取不到可用代理，可更换 `crawler.proxy.sources` 数据源，或接入自建 proxypool 服务的 HTTP 接口。

## 注意事项

- 抖音 Web 端有反爬机制且页面结构可能变化，爬取器已做容错处理：解析失败时状态记为「未知」，不中断监控。
- 爬取逻辑封装为 `DouyinCrawler` 接口，便于后续切换到抖音开放平台官方 API 或第三方数据源。
