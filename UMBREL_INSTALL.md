# Umbrel 安装与升级指南

本项目提供两种 Umbrel 部署方式：

- **路线 A（推荐）**：通过 GitHub Community App Store 安装和升级。
- **路线 B**：SSH 登录 Umbrel，使用 Docker Compose 手动部署。

两种方式都连接阿里云 RDS MySQL，不在 Umbrel 上启动 MySQL 容器。

---

## 0. 准备阿里云 RDS

1. 创建 MySQL 8.0+ 实例。
2. 创建数据库，例如 `kai_bo_ti_xing`，字符集使用 `utf8mb4`。
3. 创建专用账号，并为该账号授权访问目标数据库。
4. 如果使用公网地址，把 Umbrel/Docker 主机的公网出口 IP 加入 RDS 白名单。
5. 在 RDS 控制台确认 SSL 连接设置。

公网连接推荐参数：

```properties
db.params=useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&sslMode=REQUIRED&useSSL=true&requireSSL=true&allowPublicKeyRetrieval=false
```

如果 RDS 未开启 SSL，可改用：

```properties
db.params=useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&sslMode=DISABLED&useSSL=false&allowPublicKeyRetrieval=true
```

随后复制项目中的配置模板：

```powershell
Copy-Item src/main/resources/config/db.properties.example src/main/resources/config/db.properties
notepad src/main/resources/config/db.properties
```

至少填写：

```properties
db.host=rm-xxxxxxxxxxxx.mysql.rds.aliyuncs.com
db.port=3306
db.database=kai_bo_ti_xing
db.username=kaibotixing
db.password=<阿里云数据库密码>
db.auto.create=false
```

`db.properties` 已加入 `.gitignore`，不要提交。

---

# 路线 A：Community App Store 安装

## A1. 确认 GitHub 仓库可访问

默认仓库地址：

```text
https://github.com/xk27001/KaiBoTiXing_Server_UmbrelApp
```

仓库根目录必须包含：

```text
umbrel-app-store.yml
kaibotixing-server/umbrel-app.yml
kaibotixing-server/docker-compose.yml
```

商店 ID 为 `kaibotixing`，应用 ID 为 `kaibotixing-server`。应用 ID 必须以商店 ID 开头。

## A2. 添加社区应用商店

在 umbrelOS 中打开：

```text
App Store -> Community App Stores
```

填写 GitHub 仓库地址：

```text
https://github.com/xk27001/KaiBoTiXing_Server_UmbrelApp
```

等待商店清单刷新。

## A3. 准备应用数据目录

在 Umbrel 主机执行：

```bash
sudo mkdir -p ~/umbrel/app-data/kaibotixing-server/data/config
sudo mkdir -p ~/umbrel/app-data/kaibotixing-server/data/logs
sudo chown -R 1000:1000 ~/umbrel/app-data/kaibotixing-server
sudo chmod 700 ~/umbrel/app-data/kaibotixing-server/data/config
```

从 Windows 上传已经填写好的配置：

```powershell
scp .\src\main\resources\config\db.properties `
  umbrel@umbrel.local:~/umbrel/app-data/kaibotixing-server/data/config/db.properties
```

在 Umbrel 主机设置文件权限：

```bash
chmod 600 ~/umbrel/app-data/kaibotixing-server/data/config/db.properties
```

## A4. 安装应用

1. 返回 App Store。
2. 搜索「开播监控」。
3. 点击 Install。
4. 等待应用状态变为 ready。
5. 点击 Open，通过 umbrelOS 的登录保护访问 Web 控制台。

应用安装时会连接阿里云 RDS，并自动执行 `schema.sql` 创建或补齐业务表。

## A5. 升级应用

1. 更新仓库：

```powershell
git pull --ff-only github main
```

2. 在 GitHub Actions 中运行 `Build Umbrel image`，输入新的 `image_tag`。
3. 确认两个 Compose 文件中的 `tag@sha256:digest` 已经同步更新并推送到 GitHub。
4. 在 umbrelOS 中刷新 Community App Store。
5. 点击应用的 Update。

升级不会覆盖：

```text
~/umbrel/app-data/kaibotixing-server/data/config/db.properties
~/umbrel/app-data/kaibotixing-server/data/logs/
```

## A6. 排查安装问题

查看应用状态：

```bash
sudo docker ps -a --filter name=kaibotixing-server
sudo docker logs --tail 200 kaibotixing-server_app_1
```

重点检查：

- RDS 地址、端口、库名、用户名和密码是否正确。
- Umbrel 主机的公网出口 IP 是否在 RDS 白名单中。
- `db.params` 是否与 RDS SSL 设置一致。
- 配置目录是否可被 UID/GID `1000:1000` 读取。
- 镜像 GHCR 是否已设为 Public，或 Docker 是否已登录 GHCR。

---

# 路线 B：SSH + Docker Compose

## B1. 登录 Umbrel

```bash
ssh umbrel@umbrel.local
```

## B2. 克隆 GitHub 仓库

```bash
cd ~
git clone https://github.com/xk27001/KaiBoTiXing_Server_UmbrelApp.git
cd KaiBoTiXing_Server_UmbrelApp
```

## B3. 创建 `.env`

```bash
cat > .env <<'EOF'
KBTX_IMAGE=ghcr.io/xk27001/kai-bo-ti-xing:1.0.9-umbrel
KBTX_HTTP_PORT=8080
KBTX_DB_HOST=rm-xxxxxxxxxxxx.mysql.rds.aliyuncs.com
KBTX_DB_PORT=3306
KBTX_DB_DATABASE=kai_bo_ti_xing
KBTX_DB_USERNAME=kaibotixing
KBTX_DB_PASSWORD=请替换为阿里云数据库密码
EOF
```

生产环境建议把 `KBTX_IMAGE` 改成 compose 文件中的 `tag@sha256:digest` 完整地址。

`.env` 不要提交到 Git。

## B4. 启动服务

```bash
mkdir -p kaibotixing-server/data/logs
docker compose --env-file .env -f kaibotixing-server/docker-compose.standalone.yml up -d
docker compose --env-file .env -f kaibotixing-server/docker-compose.standalone.yml ps
```

浏览器打开：

```text
http://<umbrel-host>:8080
```

## B5. 更新服务

```bash
git pull --ff-only origin main
docker compose --env-file .env -f kaibotixing-server/docker-compose.standalone.yml pull
docker compose --env-file .env -f kaibotixing-server/docker-compose.standalone.yml up -d
```

## B6. 停止服务

```bash
docker compose --env-file .env -f kaibotixing-server/docker-compose.standalone.yml down
```

该命令不会删除 `kaibotixing-server/data/logs`。

---

## 备份建议

- 数据库使用阿里云 RDS 自动备份、快照或 DBS。
- Umbrel 侧备份 `data/config/db.properties`。
- 如需保留历史文件日志，一并备份 `data/logs/`。
