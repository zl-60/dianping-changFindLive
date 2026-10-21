# 宠伴生活 · 宠物服务与交流平台

基于原黑马点评 Java / Spring Boot 工程完成两轮改造。保留 Spring Boot、MyBatis-Plus、Redis、Lua、RabbitMQ、Redisson、MySQL 技术栈，接入宠物门店发现、限量洗护券、宠友社区、签到，以及顾客网页和店主工作台。

第二轮的功能映射、启动方式、接口、故障处理与验证说明见 [第二阶段说明](docs/petcare-stage2.md)，。

**选规格 → 先选时间 → 填宠物信息 → 下单占位 → 模拟支付 → 店主确认 → 到店核验 → 服务完成 → 评价。**

## 快速启动

需要 JDK、Maven、Redis 5+ 和 RabbitMQ。推荐使用 MySQL；也保留 H2 演示配置。前端是原生 HTML/CSS/JavaScript，由 Spring Boot 同源提供，不需要安装前端依赖。

完整 MySQL 环境：复制 `.env.example` 为 `.env`，填写本机密码，执行 `docker compose up -d --wait`，然后执行 `./start-pet.ps1 -MySql`。启动脚本会读取 `.env` 中的 `PET_*` 配置，已有环境变量优先；连接已有服务也可直接设置环境变量。Docker Compose 的 MySQL 默认使用主机端口 13306，避免与已有 MySQL 冲突。

在项目外层目录运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\start-pet.ps1
```

如需指定 Java 安装目录：

```powershell
.\start-pet.ps1 -JavaHome '你的 JDK 目录'
```

启动后访问：

- 顾客网页：http://127.0.0.1:8081/
- 店主工作台：http://127.0.0.1:8081/owner.html
- 店主演示账号：`petadmin`，默认演示密码：`PetDemo2026!`
- 顾客：填写手机号，获取本机模拟验证码后登录；查看示例订单使用 `13800000001`。

默认启动 `demo,platform`：使用文件型 H2，同时连接真实 Redis/RabbitMQ；`-MySql` 启动 `mysql,platform`。`-Demo` 可单独运行第一阶段预约演示，无需 Redis/RabbitMQ。首次启动只创建宠物业务表及演示数据；原点评源码、配置、`tb_*` 表和数据保留。第二阶段不会在依赖缺失时用内存实现冒充 Redis/RabbitMQ。

## 第一阶段规则

预约按一小时显示，服务时长为整小时；系统按能力与排班自动安排美容师。未来七天内至少提前两小时预约。未支付订单占位十分钟，支付后店主三十分钟内确认，否则取消并全额模拟退款。到店出示预约码，完成订单后可评价。

预约的业务约定、并发策略、接口和演示步骤见 [第一阶段说明](docs/petcare-stage1.md)。第二阶段已增加优惠券、门店发现与社区；预约履约仍由主门店提供，其他演示门店用于发现与探店关联。

## 验证与打包

```powershell
.\verify-pet.ps1
```

在 Windows 上先用 Windows PowerShell 5.1 检查启动与验证脚本，再验证宠物模块的数据库集成测试和网页脚本语法，并生成可运行 JAR。两个含中文的 PowerShell 脚本使用带 BOM 的 UTF-8 编码，以兼容 Windows PowerShell 5.1。

打包后可运行：

```powershell
cd dianping-master
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

默认验证第一阶段测试并打包；使用独立 Redis 后可执行 `./verify-pet.ps1 -Platform -RedisPort 16379` 验证第二阶段。真实 RabbitMQ 验证使用 `-Broker -RabbitPort 5672 -RabbitVhost /petcare-test`，必须提供独立测试虚拟主机。测试专用 Redis 使用 16379 端口的 14/15 号数据库，不能连接运行中平台使用的 Redis 数据库。原项目的其他测试仍依赖原环境，不包含在宠物验证脚本内。

网页验收需先启动应用，再运行：

```powershell
cd qa
npm ci
npm test
npm run test:platform
```

默认使用本机 Chrome，可通过 `PET_CHROME_PATH` 指定浏览器路径。验收覆盖手机布局、先选时间再填写信息、登录保留表单、支付接单和取消退款。结果及截图保存在 `.local-backups/browser-results/`，不纳入 Git。

打包后的宠物 JAR 排除原始 `application.yaml` 和含用户数据的 `hmdp.sql`；这些本地文件仍保留在源码目录。

## MySQL 模式

先创建一个数据库（默认名 `petcare`），然后设置环境变量：

```powershell
$env:PET_DB_URL = 'jdbc:mysql://127.0.0.1:3306/petcare?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8'
$env:PET_DB_USERNAME = 'root'
$env:PET_DB_PASSWORD = '你的数据库密码'
$env:PET_ADMIN_PASSWORD = '你设置的店主密码，至少八位'
.\start-pet.ps1 -MySql
```

新业务只初始化 `pet_*` 表，不删除或修改原点评表。数据库密码和店主自定义密码通过环境变量提供，不写入仓库。默认绑定本机地址；模拟支付与验证码用于本阶段演示。

## 原始备份

- 本机 `local-backup` 分支和 `backup-original-2026-10-10` 标签保存改造前的原始代码、配置与数据。
- GitHub 的 `backup-github-2026-10-10` 分支保存首次上传版本。
- `.local-backups/backup-2026-10-10.bundle` 是原始 Git 备份文件。
- 本地密码、手机号 CSV、token、原始数据库导出、演示运行数据和编译产物不纳入公开版本。

第一阶段完成版本另外保存在本机 `petcare-stage1-2026-10-10` 标签、GitHub 的 `backup-petcare-stage1-2026-10-10` 分支，以及 `.local-backups/petcare-stage1-2026-10-10.bundle` 文件中。改造前的备份继续保留。

原点评入口仍为 `com.hmdp.HmDianPingApplication`，可在 IDE 中运行；需要原有 MySQL、Redis、RabbitMQ 和本地配置。默认 Maven/JAR 入口为 `com.petcare.PetApplication`。未设置 `PET_PROFILE` 时默认启动第二阶段平台；需要纯第一阶段时显式设置 `PET_PROFILE=demo`。
