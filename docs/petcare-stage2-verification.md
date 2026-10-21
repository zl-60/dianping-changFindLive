# 第二阶段验证记录

验证日期：2026-10-21。所有运行使用独立的本机测试数据，没有写入原点评 `tb_*` 表，也没有连接原配置指向的外部数据库。

## 实际使用的环境

| 组件 | 验证版本/方式 |
| --- | --- |
| Java / Maven | JDK 25 / Maven 3.9.4；源码仍编译为 Java 8 目标 |
| Spring Boot / MyBatis-Plus / Redisson | 保留 2.3.12.RELEASE / 3.4.3 / 3.13.6 |
| Redis | 独立 Redis 5.0.14.1，127.0.0.1:16379，开启 AOF |
| RabbitMQ | 独立 RabbitMQ 4.1.4 / Erlang OTP 27，127.0.0.1:15679 |
| MySQL | 独立 MySQL 8.0.34，127.0.0.1:13306，测试库 `petcare_stage2_qa` |
| 网页 | 打包后的 JAR，`mysql,platform`，127.0.0.1:18081；Chrome + Playwright |

依赖运行文件、数据和原始日志均位于被 Git 忽略的 `.local-backups/`。Compose 配置提供另一套复现环境（MySQL 8.4、Redis 7.4、RabbitMQ 4.1），本机没有使用 Docker 运行验收。

## 后端与打包

**27 项后端集成测试通过，0 失败、0 错误、0 跳过。**

- `PetBookingIntegrationTest`：14 项原预约集成测试，覆盖履约、支付与确认超时、并发时段占用、请求幂等、取消退款、排班调整、服务快照、验证码与角色权限。
- `PetPlatformIntegrationTest`：12 项，使用真实 Redis 和 H2 MySQL 模式，覆盖 32 用户竞争 5 张券、一人一券、重复消费、数据库拒绝后的单次补偿、投递失败保留待发送记录、消费者先完成时的投递竞争、优惠券抵扣与取消退回、取消后原请求重试、Redis 会话续期和上下文清理、空值缓存与并发合并回源、Lua 所有者检查、更新后缓存失效、GEO、关注/共同关注/点赞/Feed 同时间戳分页、Bitmap，以及 500 次并发 ID 生成的唯一性。
- `PetBrokerIntegrationTest`：1 项，使用真实 Redis 和真实 RabbitMQ，从 Lua 受理经过发布确认和消费者写入数据库，验证成功结果与重复消息不重复扣库存。

最终五个网页脚本通过 `node --check`，两个启动/验证脚本通过 Windows PowerShell 5.1 解析检查，最终 JAR 打包成功。日志分别保存在 `.local-backups/verify-stage2.log` 与 `.local-backups/package-stage2.log`；最后一次页面文案调整后，JavaScript 语法单独复核并重新打包。

## 浏览器验收

两组验收使用真实 MySQL、Redis、RabbitMQ 和最终可运行 JAR，没有模拟浏览器请求响应。

1. `npm test`：原预约流程通过，覆盖手机布局、先选时间、登录保留表单、创建预约、模拟支付、店主确认、预约码、服务/人员/排班/门店管理页、取消退款和未登录权限。
2. `npm run test:platform`：第二阶段通过，覆盖附近门店与手机布局、真实异步抢券到券包、签到、发动态、点赞与点赞者列表、关注与 Feed、预约优惠券抵扣、取消后退券、店主创建草稿与发布活动。

两组浏览器脚本的 `consoleErrors` 均为空。截图已人工检查，包含手机首页、附近门店、优惠券、Feed 和桌面店主优惠券管理页。

原始结果与截图：

- `.local-backups/browser-results/result.json`
- `.local-backups/browser-platform/result.json`
- `.local-backups/browser-platform/home.png`
- `.local-backups/browser-platform/nearby.png`
- `.local-backups/browser-platform/coupons.png`
- `.local-backups/browser-platform/feed.png`
- `.local-backups/browser-platform/owner-coupons.png`

## 尚未作出的声明

这轮验证证明功能与所覆盖并发场景的正确性，没有进行吞吐量/延迟压测或多机 Redis 故障切换测试。支付、短信、退款仍为演示；多门店发现已实现，在线预约履约仍限主门店。不要把这些能力写成真实商业运营数据或生产容灾保证。
