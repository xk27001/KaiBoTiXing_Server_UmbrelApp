# 抖音主播开播监控软件

[![CI](https://github.com/xk27001/KaiBoTiXing_Server_UmbrelApp/actions/workflows/ci.yml/badge.svg)](https://github.com/xk27001/KaiBoTiXing_Server_UmbrelApp/actions/workflows/ci.yml)
[![Build Umbrel image](https://github.com/xk27001/KaiBoTiXing_Server_UmbrelApp/actions/workflows/umbrel-image.yml/badge.svg)](https://github.com/xk27001/KaiBoTiXing_Server_UmbrelApp/actions/workflows/umbrel-image.yml)
![Java 25](https://img.shields.io/badge/Java-25-orange)
![Umbrel](https://img.shields.io/badge/Umbrel-Community%20App-red)

基于 JavaFX 的抖音主播开播监控项目，同时支持 Windows 桌面端和 Umbrel/Docker Web 服务端。程序按设定间隔并发检查主播直播状态，数据持久化到阿里云 RDS MySQL，支持主播管理、实时状态、开播记录、日志查看、监控启停和代理池管理。

- GitHub：<https://github.com/xk27001/KaiBoTiXing_Server_UmbrelApp>
- Umbrel 完整安装文档：[UMBREL_INSTALL.md](UMBREL_INSTALL.md)
- Gitee 主源码仓库：<https://gitee.com/ren-daguo/kai-bo-ti-xing>

## 技术栈

- Java 25
- JavaFX 23.0.2（桌面界面）
- JDK HttpServer（Umbrel Web 控制台）
- MySQL 8.0+ / 阿里云 RDS + HikariCP + 纯 JDBC
- Jackson、SLF4J、Logback
- Maven、shade、jlink、jpackage

## 功能特性

| 模块 | 说明 |
|---|---|
| 主播管理 | 增删改查主播昵称、抖音号、房间号、主页 URL、备注和启用状态 |
| 实时监控 | 按设定间隔并发检查直播状态，展示开播、未开播和未知状态 |
| 开播记录 | 记录每次开播会话和时长 |
| 运行日志 | 写入数据库并持久化到日志目录 |
| 代理池 | 多源拉取、并发验证、轮换重试，并支持 Web 控制台手动刷新 |
| Umbrel 部署 | 社区应用商店安装，使用阿里云 RDS，umbrelOS 登录保护 Web 控制台 |

## 阿里云 RDS 配置

先在阿里云 RDS 控制台创建 MySQL 8.0+ 实例、数据库和账号。公网访问时，需要把运行 Umbrel/Docker 主机的公网 IP 加入 RDS 白名单。

复制配置模板：

```powershell
Copy-Item src/main/resources/config/db.properties.example src/main/resources/config/db.properties
```

至少修改以下字段：

```properties
db.host=rm-xxxxxxxxxxxx.mysql.rds.aliyuncs.com
db.port=3306
db.database=kai_bo_ti_xing
db.username=kaibotixing
db.password=<阿里云数据库密码>
db.params=useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&sslMode=REQUIRED&useSSL=true&requireSSL=true&allowPublicKeyRetrieval=false
db.auto.create=false
```

说明：

- 推荐先在 RDS 中创建数据库和账号，再关闭 `db.auto.create`。
- 公网连接默认启用 SSL；若实例未开启 SSL，请按示例文件中的备选参数调整 `db.params`。
- `db.properties` 包含真实密码，已加入 `.gitignore`，不要提交到仓库。
- 首次连接时程序会执行 `src/main/resources/config/schema.sql`，自动创建或补齐业务表。

## 构建与运行

```bash
mvn clean test
mvn clean package -DskipTests
mvn javafx:run
```

运行无界面 Web 服务端：

```bash
java -cp target/KaiBoTiXing-1.0.9-fat.jar com.kaibotixing.server.ServerLauncher
```

打包 Windows 免安装版本：

```powershell
powershell -ExecutionPolicy Bypass -File package-exe.ps1
```

## Umbrel 社区应用商店

仓库根目录是 Umbrel 社区应用商店，应用包位于 `kaibotixing-server/`：

```text
umbrel-app-store.yml
kaibotixing-server/
├── umbrel-app.yml                 # Umbrel 应用清单
├── docker-compose.yml             # 社区商店部署：app_proxy + 阿里云 RDS
├── docker-compose.standalone.yml  # SSH 手动部署：外部数据库环境变量
├── Dockerfile                     # linux/amd64 + linux/arm64 镜像
├── icon.svg                       # 应用图标
└── data/
    ├── config/                    # 放置 db.properties
    └── logs/                      # 持久化日志
```

### 1. 发布镜像

镜像由 GitHub Actions 工作流 `Build Umbrel image` 构建并推送到 GHCR：

```text
ghcr.io/xk27001/kai-bo-ti-xing:1.0.9-umbrel
```

在 GitHub Actions 中手动运行该工作流，`image_tag` 填写 `1.0.9-umbrel`。工作流会构建 `linux/amd64` 和 `linux/arm64` 两个平台。首次发布后，请在 GitHub Packages 中把镜像可见性设为 Public。

Compose 中的正式运行镜像使用 `tag@sha256:digest` 固定版本，避免上游 tag 被覆盖后安装内容漂移。

### 2. 添加社区应用商店

在 umbrelOS 的 **App Store → Community App Stores** 中添加：

```text
https://github.com/xk27001/KaiBoTiXing_Server_UmbrelApp
```

商店 ID 为 `kaibotixing`，应用 ID 为 `kaibotixing-server`，应用 ID 以商店 ID 开头。

### 3. 安装前配置阿里云 RDS

在 Umbrel 主机上创建配置目录：

```bash
sudo mkdir -p ~/umbrel/app-data/kaibotixing-server/data/config
sudo chown -R 1000:1000 ~/umbrel/app-data/kaibotixing-server
sudo chmod 700 ~/umbrel/app-data/kaibotixing-server/data/config
```

从本机上传 `db.properties`：

```powershell
scp .\src\main\resources\config\db.properties `
  umbrel@umbrel.local:~/umbrel/app-data/kaibotixing-server/data/config/db.properties
```

然后在 Umbrel 主机上设置权限：

```bash
chmod 600 ~/umbrel/app-data/kaibotixing-server/data/config/db.properties
```

配置完成后回到 App Store 安装「开播监控」。如果先安装后补配置，只需重启应用。

### 4. 更新与备份

更新应用只替换镜像和清单，`data/config/db.properties` 与 `data/logs/` 会保留。数据库备份请使用阿里云 RDS 自动备份或快照；Umbrel 侧只需备份配置和需要保留的日志。

## 独立 Docker 部署

`docker-compose.standalone.yml` 同样使用阿里云 RDS，不再启动本地 MySQL 容器。创建 `.env`：

```dotenv
KBTX_IMAGE=ghcr.io/xk27001/kai-bo-ti-xing:1.0.9-umbrel
KBTX_HTTP_PORT=8080
KBTX_DB_HOST=rm-xxxxxxxxxxxx.mysql.rds.aliyuncs.com
KBTX_DB_PORT=3306
KBTX_DB_DATABASE=kai_bo_ti_xing
KBTX_DB_USERNAME=kaibotixing
KBTX_DB_PASSWORD=<阿里云数据库密码>
```

启动：

```bash
mkdir -p kaibotixing-server/data/logs
docker compose --env-file .env -f kaibotixing-server/docker-compose.standalone.yml up -d
```

浏览器打开 `http://<主机>:8080`。`.env` 包含数据库密码，不要提交到 Git。

## 代理池配置

代理池配置可以写入 `db.properties`，Web 控制台中的运行设置会持久化到数据库 `monitor_config`：

```properties
crawler.proxy.enabled=true
crawler.proxy.refresh.minutes=40
crawler.proxy.validate.parallelism=200
crawler.proxy.validate.sample.count=250
crawler.proxy.validate.url=https://live.douyin.com/
crawler.retry.count=15
```

代理池不可用或为空时程序会退化为直连，避免监控完全中断。

## 注意事项

- 抖音页面结构可能变化，解析失败时状态记为“未知”，不会停止监控。
- 阿里云 RDS 必须允许 Umbrel/Docker 主机的公网出口 IP 访问。
- 不要提交 `db.properties`、`.env`、数据库密码或 GitHub Token。
- 如果修改镜像所有者，请同步修改两个 Compose 文件、工作流和安装文档。

## 安全与支持

安全问题请参阅 [SECURITY.md](SECURITY.md)。问题和功能建议请提交到 GitHub Issues。
