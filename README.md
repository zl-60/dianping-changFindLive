# 黑马点评项目备份

Java 8 / Spring Boot 2.3.12 / Maven 项目，应用目录为 `dianping-master/`。

## 本地运行

1. 准备 MySQL、Redis 和 RabbitMQ。
2. 将 `dianping-master/src/main/resources/application.example.yaml` 复制为同目录的 `application.yaml`，按本机环境设置连接信息。配置示例使用环境变量 `MYSQL_PASSWORD` 和 `RABBITMQ_PASSWORD`。
3. 按实际数据库名称导入 `dianping-master/src/main/resources/db/hmdp.schema.sql`，并自行准备测试数据；该文件只包含表结构。
4. 在 `dianping-master/` 目录运行 `mvn spring-boot:run`，默认端口为 8081。

## 备份说明

- GitHub 的 `main` 分支和 `backup-github-2026-10-10` 备份分支保存首次上传的源码版本。
- 原电脑 `D:\dianping-master` 中的 `local-backup` 分支及 `backup-original-2026-10-10` 标签保存上传前的原始源码、配置与数据。
- 原始配置、手机号 CSV、登录 token 和含用户数据的数据库导出仅在本地保存；编译产物和 IDE 缓存不纳入 Git。
- `local-backup` 分支包含本地配置和数据，请仅推送 `main` 及明确指定的公开标签。
- `generate_tokens.py` 是本地辅助脚本，其中的端口和文件路径需按运行环境调整。
