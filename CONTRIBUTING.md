# 贡献指南

感谢参与 KaiBoTiXing。

## 开发环境

- JDK 25
- Maven 3.9+
- MySQL 8.0 或 8.4
- Docker（可选，用于 Umbrel 镜像）
- Node.js（可选，用于检查 Web 控制台 JavaScript）

## 本地验证

```bash
mvn -B -ntp test
mvn -B -ntp -DskipTests package
node --check src/main/resources/web/app.js
```

## 分支和提交

- 功能分支使用 `codex/` 前缀，例如 `codex/web-console`。
- 提交信息建议使用 Conventional Commits：
  - `feat: add ...`
  - `fix: correct ...`
  - `docs: update ...`
  - `build: change ...`
- 不要提交数据库密码、Token、`.env` 或 `db.properties`。

## 提交 Pull Request

请在 Pull Request 中说明：

1. 修改目的。
2. 主要实现。
3. 验证方式。
4. 是否影响 Umbrel 应用清单、Docker 镜像或数据库结构。

## Umbrel 清单变更

修改以下文件时，请确保版本号保持一致：

- `kaibotixing-server/umbrel-app.yml`
- `kaibotixing-server/docker-compose.yml`
- `.github/workflows/umbrel-image.yml`
