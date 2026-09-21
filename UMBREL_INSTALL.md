# 将 KaiBoTiXing 安装到 Umbrel：完整步骤

本文给出两条路线：

- **路线 A：Umbrel 社区应用商店安装（推荐）**：可以从 umbrelOS 应用商店一键安装、升级。社区商店地址必须使用 GitHub，因此 Gitee 需要同步一份 GitHub 镜像。
- **路线 B：仅使用 Gitee + SSH 手动安装**：不需要 GitHub，但不会出现在 Umbrel 应用商店中，也不提供一键升级。

项目已使用 **MySQL 8.4**，对应的 Compose 文件是：

- `kaibotixing-server/docker-compose.yml`：Umbrel 社区商店使用，包含 `app_proxy`。
- `kaibotixing-server/docker-compose.standalone.yml`：SSH 手动安装使用，直接暴露 `8080` 端口。

---

## 0. 先替换下面这些变量

后续命令中的尖括号内容需要替换成你的实际值：

| 变量 | 示例 | 说明 |
|---|---|---|
| `<github-user>` | `xk27001` | GitHub 用户名或组织名 |
| `<github-repo>` | `KaiBoTiXing_Server_UmbrelApp` | GitHub 仓库名 |
| `<gitee-user>` | `ren-daguo` | Gitee 用户名 |
| `<gitee-repo>` | `kai-bo-ti-xing` | Gitee 仓库名 |
| `<image-owner>` | `xk27001` | GHCR 或 Docker Hub 镜像所有者 |
| `<umbrel-host>` | `umbrel.local` | Umbrel 主机名或 IP |

推荐的镜像地址：

```text
ghcr.io/<github-user>/kai-bo-ti-xing:1.0.2-umbrel
```

---

# 路线 A：通过 Umbrel 社区应用商店安装

## A1. 确认本地代码完整

当前工作目录应为：

```text
D:\aa_JavaSpaces\KaiBoTiXing
```

确认当前分支：

```powershell
git branch --show-current
```

应看到：

```text
codex/umbrel-app
```

确认 Umbrel 文件存在：

```powershell
Test-Path umbrel-app-store.yml
Test-Path kaibotixing-server\umbrel-app.yml
Test-Path kaibotixing-server\docker-compose.yml
Test-Path kaibotixing-server\Dockerfile
```

四个结果都应为 `True`。

## A2. 修改镜像所有者

当前仓库对应的 GitHub 用户是 `xk27001`，编辑：

```text
kaibotixing-server/docker-compose.yml
```

把：

```yaml
image: ghcr.io/xk27001/kai-bo-ti-xing:1.0.2-umbrel
```

改成：

```yaml
image: ghcr.io/<github-user>/kai-bo-ti-xing:1.0.2-umbrel
```

`ghcr.io` 后面的用户名必须与 GitHub 实际所有者完全一致，并且全部使用小写字母。

## A3. 提交 Umbrel 改造

在项目根目录执行：

```powershell
git add .
git commit -m "feat: add Umbrel and MySQL 8 deployment"
```

如果你是第一次在本机提交 Git，先配置身份：

```powershell
git config user.name "你的名字"
git config user.email "你的邮箱"
```

## A4. 将完整项目推送到 GitHub

Umbrel 社区应用商店需要 GitHub 仓库。建议把 Gitee 仓库完整镜像到 GitHub，不要只上传清单文件，因为 GitHub Actions 需要使用 `pom.xml`、`src/` 和 Dockerfile 构建镜像。

1. 登录 GitHub。
2. 新建公开仓库，例如：

```text
https://github.com/<github-user>/<github-repo>
```

3. 不要勾选自动创建 README。
4. 回到本地项目，添加 GitHub 远程仓库：

```powershell
git remote add github https://github.com/<github-user>/<github-repo>.git
```

如果提示远程已存在：

```powershell
git remote set-url github https://github.com/<github-user>/<github-repo>.git
```

5. 推送当前分支：

```powershell
git push -u github codex/umbrel-app
```

6. 打开 GitHub 仓库，进入 **Settings → General → Default branch**，把默认分支设置为：

```text
codex/umbrel-app
```

Umbrel 会从仓库默认分支读取根目录的 `umbrel-app-store.yml`。

## A5. 构建并发布容器镜像

### 方式一：使用 GitHub Actions

1. 打开 GitHub 仓库。
2. 进入 **Actions**。
3. 选择左侧的 **Build Umbrel image**。
4. 点击 **Run workflow**。
5. 选择 `codex/umbrel-app` 分支并执行。
6. 等待工作流全部变绿。

工作流会构建 `linux/amd64` 和 `linux/arm64` 镜像，并推送到：

```text
ghcr.io/<github-user>/kai-bo-ti-xing:1.0.2-umbrel
```

### 方式二：在本地 Docker 中构建

本地必须已经安装 Docker Desktop。

```bash
docker login ghcr.io
docker build -f kaibotixing-server/Dockerfile -t ghcr.io/<github-user>/kai-bo-ti-xing:1.0.2-umbrel .
docker push ghcr.io/<github-user>/kai-bo-ti-xing:1.0.2-umbrel
```

登录 GHCR 时，用户名填写 GitHub 用户名，密码填写具有 `write:packages` 权限的 GitHub Personal Access Token。

## A6. 把 GHCR 镜像设置为公开

这是最容易遗漏的一步。默认情况下，第一次推送的 GHCR 包可能是私有的，Umbrel 无法匿名拉取。

1. 打开 GitHub 个人主页。
2. 进入 **Packages**。
3. 找到 `kai-bo-ti-xing`。
4. 进入 **Package settings**。
5. 找到 **Danger Zone**。
6. 选择 **Change visibility → Public**。
7. 确认公开。

可以在没有登录 GHCR 的机器上验证：

```bash
docker manifest inspect ghcr.io/<github-user>/kai-bo-ti-xing:1.0.2-umbrel
```

如果能够返回 manifest，说明 Umbrel 可以拉取。

## A7. 在 Umbrel 中添加社区应用商店

1. 在浏览器打开：

```text
http://<umbrel-host>
```

2. 登录 umbrelOS。
3. 打开 **App Store**。
4. 点击右上角 **...** 菜单。
5. 选择 **Community App Stores**。
6. 在地址框中填写：

```text
https://github.com/<github-user>/<github-repo>
```

7. 点击 **Add**。
8. 等待 Umbrel 读取商店清单。

如果添加失败，依次检查：

- GitHub 仓库是公开的。
- `umbrel-app-store.yml` 位于仓库根目录。
- `kaibotixing-server/umbrel-app.yml` 存在。
- 仓库默认分支包含上述文件。
- 应用 ID `kaibotixing-server` 以商店 ID `kaibotixing` 开头。

## A8. 从商店安装应用

1. 返回 **App Store**。
2. 搜索：

```text
开播监控
```

3. 点击应用卡片。
4. 点击 **Install**。
5. 等待以下步骤完成：
   - 拉取 KaiBoTiXing 应用镜像。
   - 拉取 MySQL 8.4 镜像。
   - 初始化 `kai_bo_ti_xing` 数据库。
   - 执行建表脚本。
   - 启动 Web 控制台。
6. 安装完成后点击 **Open**。
7. 如果 umbrelOS 要求登录，使用 Umbrel 用户名和密码，不需要单独创建应用账号。

## A9. 第一次使用

1. 打开 **主播管理**。
2. 点击 **新增主播**。
3. 至少填写：
   - 主播昵称。
   - 抖音号或主页标识。
4. 如果知道直播间房间号，建议填写 `web_rid`，例如：

```text
https://live.douyin.com/918650447703
```

只填写数字部分：

```text
918650447703
```

5. 保存主播。
6. 点击 **启动监控**。
7. 打开 **监控状态**，等待第一轮检测完成。
8. 如果状态是“未知”，通常是抖音反爬导致，稍后会自动重试；也可以补充正确的 `web_rid`。
9. 在 **日志** 中查看检测过程。
10. 在 **代理池** 中查看免费代理池状态。

## A10. 查看容器日志和排错

在 Umbrel 主机上执行：

```bash
ssh umbrel@<umbrel-host>
```

查看运行中的容器：

```bash
sudo docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" | grep kaibotixing
```

查看应用日志：

```bash
sudo docker logs --tail 200 kaibotixing-server_app_1
```

查看 MySQL 日志：

```bash
sudo docker logs --tail 200 kaibotixing-server_db_1
```

如果容器名不完全一致，执行：

```bash
sudo docker ps
```

然后使用实际容器名。

## A11. 更新 Umbrel 应用

每次发布新版本时，需要同步修改三个位置：

1. `kaibotixing-server/umbrel-app.yml`：

```yaml
version: "1.0.2-umbrel"
```

2. `kaibotixing-server/docker-compose.yml`：

```yaml
image: ghcr.io/<github-user>/kai-bo-ti-xing:1.0.2-umbrel
```

3. `.github/workflows/umbrel-image.yml` 中的镜像标签：

```yaml
tags: |
  ${{ steps.image.outputs.name }}:1.0.2-umbrel
  ${{ steps.image.outputs.name }}:latest
```

提交并推送后：

1. 运行 **Build Umbrel image** 工作流。
2. 在 umbrelOS 的社区商店刷新应用列表。
3. 点击应用中的 **Update**。

## A12. 数据目录和备份

Umbrel 应用数据通常位于：

```text
~/umbrel/app-data/kaibotixing-server/data
```

其中：

```text
data/db      MySQL 8 数据
data/logs    应用日志
```

完整停止应用后再备份：

```bash
sudo docker stop kaibotixing-server_app_1 kaibotixing-server_db_1
tar -czf ~/kai-bo-ti-xing-backup-$(date +%F).tar.gz \
  ~/umbrel/app-data/kaibotixing-server/data
sudo docker start kaibotixing-server_db_1 kaibotixing-server_app_1
```

---

# 路线 B：仅使用 Gitee + SSH 手动安装

这条路不需要 GitHub，但不能从 Umbrel 应用商店安装，也没有 umbrelOS 的自动更新集成。

## B1. 构建并发布一个公开镜像

可以使用 Docker Hub，例如：

```bash
docker login
docker build -f kaibotixing-server/Dockerfile -t <dockerhub-user>/kai-bo-ti-xing:1.0.2-umbrel .
docker push <dockerhub-user>/kai-bo-ti-xing:1.0.2-umbrel
```

在 Docker Hub 中把仓库设为 Public。

## B2. 将代码推送到 Gitee

```powershell
git push origin codex/umbrel-app
```

## B3. SSH 登录 Umbrel

```bash
ssh umbrel@<umbrel-host>
```

## B4. 克隆 Gitee 项目

```bash
cd ~
git clone -b codex/umbrel-app https://gitee.com/<gitee-user>/<gitee-repo>.git
cd <gitee-repo>
```

## B5. 创建独立部署环境文件

```bash
cat > .env <<'EOF'
KBTX_IMAGE=<dockerhub-user>/kai-bo-ti-xing:1.0.2-umbrel
MYSQL_PASSWORD=请替换为至少16位强密码
KBTX_HTTP_PORT=8080
EOF
```

不要把 `.env` 提交到 Git。

## B6. 创建数据目录并启动

```bash
mkdir -p kaibotixing-server/data/logs
docker compose \
  --env-file .env \
  -f kaibotixing-server/docker-compose.standalone.yml \
  up -d
```

查看状态：

```bash
docker compose \
  --env-file .env \
  -f kaibotixing-server/docker-compose.standalone.yml \
  ps
```

浏览器打开：

```text
http://<umbrel-host>:8080
```

如果无法访问，检查端口：

```bash
sudo ss -lntp | grep 8080
```

## B7. 更新手动安装

```bash
cd ~/<gitee-repo>
git pull
docker compose \
  --env-file .env \
  -f kaibotixing-server/docker-compose.standalone.yml \
  pull
docker compose \
  --env-file .env \
  -f kaibotixing-server/docker-compose.standalone.yml \
  up -d
```

## B8. 停止手动安装

```bash
docker compose \
  --env-file .env \
  -f kaibotixing-server/docker-compose.standalone.yml \
  down
```

`down` 不会删除 `kaibotixing-server/data`，数据库和日志仍会保留。

---

# 最终推荐

如果你的目标是“在 Umbrel 应用商店里一键安装和升级”，请使用 **路线 A**。Gitee 可以继续作为主源码仓库，但必须至少维护一个公开 GitHub 镜像仓库用于社区商店和 GHCR 镜像构建。

如果你的目标是“完全不使用 GitHub”，请使用 **路线 B**。它能在 Umbrel 上运行，但属于手动 Docker 部署，不是 Umbrel 商店应用。
