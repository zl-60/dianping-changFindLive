# 宠伴生活第二阶段面试技术事实核对

核对日期：2026-10-21。本文对照当前 `com.petcare` 源码与官方文档，帮助区分已经实现的能力、成立条件和后续改进。项目依赖仍为 Spring Boot 2.3.12、Spring AMQP 2.2.18、Redisson 3.13.6；Java 编译目标为 8。引用新版 Redis 文档只用于长期稳定的执行与持久化语义，不表示项目已经升级到该文档展示的版本。RabbitMQ 使用 4.1 版文档，Spring 事务使用 5.2.15 版资料。

## 1 Redis Lua 原子执行不等于异常自动回滚

**可以回答：** Lua 把库存、限购和待发送记录的正常更新放在 Redis 服务端连续执行，其他客户端命令不能在脚本中间插入。这样可以避免先 GET 再 DECR 的并发竞争，也减少网络往返。脚本仍应短小，长循环会阻塞其他请求。[Redis 脚本执行说明](https://redis.io/docs/latest/develop/programmability/eval-intro/)

**不能回答：** Lua 后半段出错，前面的写入会像 MySQL 事务一样自动撤销。Redis 没有这种自动回滚保证。[Redis 官方回滚说明](https://redis.io/blog/you-dont-need-transaction-rollbacks-in-redis/)

**当前代码：** `lua/pet-claim.lua` 明确返回成功码 0，先校验已有领取人、库存与活动时间，再扣库存、记录买家、写请求 Hash 和待发送 ZSet。它没有对所有待写 Key 进行 TYPE 预检；假如请求 Key 被错误写成 String，后面的 HSET 可以失败，而前面的 DECR 已生效。因此“正常脚本完成时预扣与待发送记录一起写入”有源码支撑，“任何 Redis 错误下都自动恢复”没有。

**改进方向：** 写入前检查参数、Key 类型及库存数据格式，限制命名空间的写入者，设计告警与对账；这些也不能把 Redis 和 MySQL 变成一个共同事务。

## 2 Redis 中的待发送记录是不是数据库事务消息

当前 `PetCouponRelay` 每秒扫描到期的 pending ZSet，发送消息并等待确认；请求 Hash 和 pending 与库存由同一个 Lua 写入。这能覆盖“请求预扣后，Java 进程在发送前退出”的窗口，前提是 Redis 中的这些记录仍然存在。

它是 Redis 保存的待发送任务，不是“在 MySQL 业务事务中插入 outbox 表”的实现，也不是 RabbitMQ 原生事务消息。Redis AOF、Redis 复制和 MySQL 各自有故障边界，不能据此宣称跨组件强一致。当前所有脚本 Key 都显式通过 KEYS 传入，并带相同 hash tag；这符合多 Key 脚本同槽规划思路，但运行配置仍是单节点 Redis，未验证 Redis Cluster。[Redis 脚本与 Key 参数](https://redis.io/docs/latest/develop/programmability/eval-intro/)

## 3 开启 AOF everysec 后还有丢失窗口吗

有。AOF 记录写操作，`appendfsync everysec` 通常每秒同步磁盘，在主机故障时仍可能损失最近约一秒的写入；RDB 是时间点快照，可能丢失两次快照之间的数据。持久化降低数据损失风险，不提供跨系统事务。[Redis 持久化说明](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)

**当前代码：** Compose 使用 AOF everysec 与 noeviction，避免待发送记录像普通缓存一样被淘汰；noeviction 达到内存上限时可能拒绝新增写入，它不保证写入始终可用。[Redis 淘汰策略](https://redis.io/docs/latest/develop/reference/eviction/) 正常应用重启可以继续扫描任务；人工删除部分库存、buyers、request 或 pending Key，不能靠“重新发布活动”自动重建完整历史。Redis 全量灾难恢复与自动业务对账尚未实现。

## 4 Publisher confirm 和 consumer ACK 分别证明什么

Confirm 覆盖生产者到代理的投递确认，consumer ACK 覆盖代理到消费者的处理确认；生产者收到确认时，消费者可能还没开始执行。确认响应丢失时，发送方无法仅凭超时判断代理是否收到了消息，重发就可能产生重复。[RabbitMQ 4.1 确认机制](https://www.rabbitmq.com/docs/4.1/confirms)

**当前代码：** `PetCouponRelay` 为持久化消息附带业务订单 ID 和 CorrelationData，等待最多五秒；仅在确认通过且没有返回消息时调整下一次扫描时间。订单是否成功以数据库和结果接口为准，不以发送成功为准。Redis pending 直到消费者完成才移除，因此“代理已确认但消费者迟迟未完成”也可能触发再次投递，消费必须幂等。

## 5 mandatory return 为什么还需要单独检查

消息即使被交换机接收，也可能没有匹配的队列。开启 mandatory 后，无法路由的消息会被返回；confirm 和 return 回答的问题不同，不能只检查“发送未抛异常”或“收到 ack”。[RabbitMQ 4.1 无法路由消息与确认顺序](https://www.rabbitmq.com/docs/4.1/confirms#when-publishes-are-confirmed)

当前 `pet.yml` 开启 correlated confirms、publisher returns 和 mandatory；`PetCouponRelay` 同时检查确认与 `getReturnedMessage()`，不满足条件就保留待发送任务。这是当前真实实现，可以展开说明；不要把这句话扩大为“所有代理故障都不会丢消息”。

消费业务性失败会持久化 FAILED；`pet-claim-finish.lua` 只在状态还是 PENDING 时补偿一次库存并移除待发送记录。它保留 buyers 中的用户与订单映射，所以同一用户再次领取会查到原失败结果，不能说“补偿后一定允许该用户重新抢券”。

## 6 Spring 的 AUTO 是不是 RabbitMQ 收到即确认

不是。Spring AMQP 的 AUTO 模式由监听容器依据监听方法是否正常完成决定确认；MANUAL 由业务代码显式确认；NONE 才对应不等待业务处理的代理自动确认语义。2.2.18 的容器源码默认 `AcknowledgeMode.AUTO`，注释明确区分三种模式。[Spring AMQP 2.2.18 容器源码](https://github.com/spring-projects/spring-amqp/blob/v2.2.18.RELEASE/spring-rabbit/src/main/java/org/springframework/amqp/rabbit/listener/AbstractMessageListenerContainer.java)

**当前代码：** 消费监听没有配置 MANUAL，也没有调用 `basicAck`。`PetCouponConsumer` 先完成数据库事务，再执行 Redis 结果更新 Lua，最后正常返回；容器随后确认。数据库提交后、Redis 更新前异常会触发重试，下一次先读取已存在订单，不再次扣库存。这里有数据库与 Redis 的短暂不同步，依靠重试收敛，不是原子提交。

## 7 三次重试和三十秒 TTL 是不是最多尝试三次

不是。`pet.yml` 的 `max-attempts: 3` 限制的是一轮监听器重试；主队列配置死信到重试队列，重试队列 TTL 三十秒后再死信回主队列，会开始新一轮处理。TTL 约束消息在队列中停留的时间，并不保证精确在某个业务时刻执行。[RabbitMQ 4.1 TTL](https://www.rabbitmq.com/docs/4.1/ttl)、[死信机制](https://www.rabbitmq.com/docs/4.1/dlx)

**当前边界：** 没有跨所有投递轮次的总次数上限、最终停放队列或人工处理状态；Redis pending 也可能继续补发。永久性错误可能反复流转，不能说“已实现有限重试加人工兜底”。生产方案可以增加总重试次数、退避、告警、不可重试异常分类和受控补偿。

这里的 TTL 用于抢券消息失败后重试；预约十分钟未支付和三十分钟待确认超时，实际由数据库 deadline、定时清理及业务操作前清理实现，不能把两个机制混讲。

## 8 持久化队列加持久化消息为什么仍不能说绝不丢失

当前声明 durable 交换机/队列、持久化消息，并使用生产者确认，但没有声明 quorum queue、跨机副本策略或验证代理集群故障。默认死信重新发布本身也可能失败；官方说明，默认 DLX 转发不会自动提供带发布确认的安全转发，支持相应能力的 quorum 配置需要另外落实。[RabbitMQ 4.1 死信转发安全性](https://www.rabbitmq.com/docs/4.1/dlx#safety)

适合回答：“我覆盖了应用退出、发送失败、结果更新失败和重复投递等常见窗口，数据库落单幂等；代理持久化、Redis 恢复、网络分区和多机容灾仍有各自边界，没有做端到端零丢失承诺。”

## 9 Redisson 3.13.6 的 watchdog 何时启用

对照固定版本源码，不指定 leaseTime 的 `lock()`、`tryLock()` 使用 watchdog 超时并注册续期；显式指定 leaseTime 时走定时租约路径。本项目配置 watchdog 为三十秒，续期任务按内部租约的三分之一调度，正常情况下约十秒尝试续期。可重入通过所有者和计数实现，释放时检查线程所有权。[Redisson 3.13.6 RedissonLock 源码](https://github.com/redisson/redisson/blob/redisson-3.13.6/redisson/src/main/java/org/redisson/RedissonLock.java)

**当前代码：** 抢券消费按订单 ID 加锁，覆盖数据库提交和 Redis 结果更新，并在 finally 中解锁；预约资源仍使用数据库 `pet_lock` 行锁。不要把项目说成“所有并发控制都改为 Redisson”。

## 10 watchdog 能不能保证持锁业务绝不并行

不能。进程长时间停顿或网络异常可能让租约过期；旧业务恢复时仍可能继续操作，自动续期不是对外部系统写入的 fencing token。异步复制、故障切换也影响 Redis 锁的安全条件。[Redis 分布式锁的安全条件](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/)

因此本项目仍保留数据库唯一键和条件扣库存作最终约束。手写 `SimpleRedisLock` 用 SET NX 加过期时间获取，UUID 与线程 ID 标识所有者，Lua 比较后删除；它解决误删别人的锁，未实现自动续期或锁丢失后的外部写入隔离。标准 Redisson RLock 与 Redlock 算法也不是同一个名词。

## 11 Spring 事务代理和 TransactionTemplate 如何对应当前代码

默认代理模式只拦截经过代理的调用，`this.xxx()` 不会因为被调方法上有事务注解就额外启用事务；传统事务上下文绑定当前线程，也不会自动传播到新建线程或 MQ 消费线程。[Spring 5.2 事务代理](https://docs.spring.io/spring-framework/docs/5.2.15.RELEASE/spring-framework-reference/data-access.html#transaction-declarative-annotations)、[Transactional API](https://docs.spring.io/spring-framework/docs/5.2.15.RELEASE/javadoc-api/org/springframework/transaction/annotation/Transactional.html)

**当前代码：** 预约服务从 Controller 经 Spring Bean 进入类级事务；消费线程内部使用 `TransactionTemplate.execute` 包住条件扣库存和券订单插入，异常时整笔数据库操作回滚。模板不依赖“调用自身的注解方法”启动事务，默认传播是 REQUIRED，不是每次强制开启 REQUIRES_NEW。在当前没有外围数据库事务的消费路径上，execute 正常返回时本地事务已提交。[TransactionTemplate API](https://docs.spring.io/spring-framework/docs/5.2.15.RELEASE/javadoc-api/org/springframework/transaction/support/TransactionTemplate.html)

JdbcTemplate 和 MyBatis-Plus 能参与同一个事务，条件是 MyBatis-Spring 与事务管理器使用同一个 DataSource。本项目沿用同一数据源的 Spring 事务管理；多数据源或 XA 是另外的问题。[MyBatis-Spring 事务说明](https://mybatis.org/spring/transactions.html)

## 12 noRollbackFor 是真正需要解释的设计取舍

默认规则对 RuntimeException/Error 回滚，普通受检异常不自动回滚；`noRollbackFor` 则明确排除对应异常。[Spring 5.2 回滚属性](https://docs.spring.io/spring-framework/docs/5.2.15.RELEASE/javadoc-api/org/springframework/transaction/annotation/Transactional.html#noRollbackFor--)

`PetException` 继承 RuntimeException，预约服务却设置 `noRollbackFor=PetException.class`，目的是即使当前请求因业务条件失败，先执行的过期订单清理也可以提交；验证码错误次数也需要避免因抛错而撤销。该规则覆盖整个服务，并非只影响清理步骤，因此依赖“可能抛业务异常的验证尽量在新增写入之前完成”。后续开发若在多次写入之间抛 PetException，可能提交部分业务变更。更细的独立清理事务或异常分类属于改进方案，不应声称当前所有业务异常都会回滚。

## 13 MySQL 条件更新与唯一约束怎样防超卖和重复

`UPDATE pet_coupon SET stock=stock-1 WHERE id=? AND stock>0 ...` 将判断与修改放在一条语句，InnoDB 对写入记录加锁并协调竞争，Java 必须检查影响行数。这是条件扣减，不是 `SELECT stock` 后在 Java 中判断，也不是使用 version 字段的乐观锁实现。[MySQL 8.0 不同语句的锁](https://dev.mysql.com/doc/refman/8.0/en/innodb-locks-set.html)

当前消费事务将扣减和订单插入绑定；订单 ID 主键处理相同消息，`UNIQUE(user_id,coupon_id)` 约束同人同活动；发生未处理的数据库异常会使整笔消费事务回滚。`pet_slot(staff_id,slot_start)` 主键则兜底人员时段重复占用。先查不存在只能改善处理流程，唯一约束才能在并发下兜底。

预约的 `SELECT id FROM pet_lock WHERE id=1 FOR UPDATE` 必须在有效事务内执行，锁随提交或回滚释放；它串行化主门店资源操作，便于正确性验证，也限制吞吐量。[MySQL 8.0 锁定读](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html)

## 14 MVCC 不能替代库存锁或唯一约束

InnoDB 在 REPEATABLE READ 下，普通一致性读通常复用同一事务首次一致性读建立的快照；READ COMMITTED 下，每次一致性读建立新快照。普通 SELECT 和 UPDATE/FOR UPDATE 的读取及加锁语义不同，不能把快照读结论套给扣库存语句。[MySQL 8.0 一致性非锁定读](https://dev.mysql.com/doc/refman/8.0/en/innodb-consistent-read.html)

面试时可以回答：MVCC 减少普通读与写之间的阻塞，不负责把任意“先查询再插入”变成原子业务操作。当前项目没有显式改变事务隔离级别，应检查实际数据库会话配置；不能把 H2 MySQL 模式下的测试结果当成 MySQL 所有锁细节的证明。

## 15 会话续期与验证范围的准确表述

`PetWebConfig` 先解析会话与注入上下文，再执行登录校验；Redis 会话在合法请求时把 TTL 刷新为三十分钟。这是请求驱动的滑动过期，没有请求时不会后台无限续期。`afterCompletion` 清理 ThreadLocal，防止工作线程复用时残留身份；角色与资源归属仍由服务端检查，不能仅凭“已经登录”访问别人的订单。

本轮已经有并发正确性与真实中间件链路验证；测试通过不等于测得高并发吞吐、故障切换或零丢失。可以描述实际测试场景和断言，不应编造 QPS、P99 或提升百分比。详细问题答案以主面试手册为主，本文用于核对技术语义与追问边界。
