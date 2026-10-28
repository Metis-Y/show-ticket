# 消息队列：RabbitMQ 异步下单与延迟关单

> 简历一句话：RabbitMQ 异步下单削峰，TTL + 死信队列实现订单超时自动取消与库存回补，消费端幂等 + 失败重试 + 死信兜底。

## 1. 业务场景

1. 抢票成功后需要落库（扣库存 + 插订单），不能让用户等数据库。
2. 订单创建后如果一直不支付，需要自动取消并把票还回库存。

## 2. 队列拓扑

```
ticket.exchange (direct)
  ├─ ticket.grab    → ticket.grab.queue     抢票下单（有消费者）
  ├─ ticket.delay   → ticket.delay.queue    延迟队列（无消费者，只等 TTL）
  └─ ticket.timeout → ticket.timeout.queue  TTL 到期后由死信路由过来（有消费者）

ticket.dlx (direct)
  └─ ticket.dead    → ticket.dead.queue     消费重试耗尽后的死信
```

vhost 使用独立的 `show-ticket`，和同一台 RabbitMQ 上的其他项目隔离。

## 3. 两个核心流程

### 3.1 异步下单

抢票接口 Lua 通过后发消息 → `GrabOrderConsumer` 消费 → `createTicketOrder` 在事务内「扣 DB 库存 + 插订单」。

**幂等**：订单号在入口生成并随消息传递；消费端先按订单号查一次，已存在直接返回。RabbitMQ 是 at-least-once，重复投递是常态，幂等必须落在业务侧。

### 3.2 延迟关单（TTL + 死信）

1. 下单时把消息投到 `ticket.delay.queue`，该队列设置 `x-message-ttl = app.order.timeout-ms`；
2. 队列**没有消费者**，消息到期后成为死信，按 `x-dead-letter-exchange/routing-key` 路由到 `ticket.timeout.queue`；
3. 消费者检查订单状态：已支付什么都不做；未支付则 `status = 1` 条件更新为已取消，**只有更新成功的那次才回补库存**；
4. 订单还没落库（下单消息还在排队）→ 抛异常让 MQ 稍后重试。

**回补三处**：MySQL 库存 +1、Redis 库存 +1、移除限购占位。

## 4. 可靠性设计

| 机制 | 配置/实现 |
| --- | --- |
| 消费重试 | `spring.rabbitmq.listener.simple.retry`：3 次、指数退避 |
| 重试耗尽 | `default-requeue-rejected: false` + 队列 DLX → `ticket.dead.queue`，避免无限重回队列打满 CPU |
| 幂等 | 订单号查重 + 数据库主键 |
| 并发安全 | 关单和支付都是 `status = 1` 条件更新，谁先成功谁生效 |
| 发送失败 | 捕获异常，回补 Redis 库存与限购占位 |

## 5. 为什么用 RabbitMQ

| 方案                                   | 优点                            | 为什么不用                                      |
| ------------------------------------ | ----------------------------- | ------------------------------------------ |
| **RabbitMQ（选用）**                     | 内存占用小、路由灵活、开箱即用、TTL+DLX 能实现延迟 | —                                          |
| RocketMQ                             | 自带延迟级别、事务消息、高吞吐               | broker 默认 4G 堆，本地开发太重；延迟级别固定且不够灵活          |
| Kafka                                | 吞吐最高                          | 偏日志/流处理，延迟消息和单条重试要自己做                      |
| Redis Stream（课程原方案）                  | 不引入新中间件                       | 重试、死信、堆积监控都要自己维护 pending-list，本质上是在手写一个 MQ |
| 定时任务扫表                               | 实现简单                          | 有扫描周期延迟、DB 压力随订单量增长，且和大促流量叠加               |
| rabbitmq-delayed-message-exchange 插件 | 支持任意延迟                        | 需要额外安装插件，TTL+DLX 已经够用，且延迟配置可以随队列走          |

## 6. 踩过的坑

1. **vhost 名必须和服务端完全一致**：配置写 `/show-ticket`，服务端建的是 `show-ticket`，启动后发送报 `NOT_ALLOWED - vhost not found`。
2. **队列 TTL 是队列级参数**：修改 `app.order.timeout-ms` 后必须先删除 `ticket.delay.queue` 再重启，否则新参数不会生效。
3. **MQ 不可达时接口会被拖住**：跨网段连不上是 TCP 超时而不是 connection refused，抢票接口会等十几秒。当前未加 `connection-timeout` 快速失败配置，属于已知优化点。
4. **延迟队列不能挂消费者**：挂了就变成普通队列，消息会被立刻消费掉，延迟失效。
5. **重试不要用无限 requeue**：失败消息立刻重回队列会形成死循环，打满 CPU。

## 7. 实测

- 抢票 → 消费落库：30ms
- 不支付等待 60 秒 → `17:52:50` 收到超时消息 → 订单 `status=4`，Redis 库存 49→50，限购占位释放
- 再次抢票成功（新订单号）
- 管理台队列状态：`grab=0 / delay=1（待到期的下一单）/ timeout=0 / dead=0`
- MQ 停掉时抢票：返回「系统繁忙」，库存回补

## 8. 面试追问

- Q：消息会丢吗？→ 当前只保证生产者同步发送成功 + 消费端手动 ack 语义（auto ack + 异常抛出让容器重投），**没有开 publisher confirm**，极端情况下 broker 收到前宕机可能丢消息；改进方案是本地消息表或 publisher confirm + return。
- Q：消息会重复吗？→ 会。at-least-once，所以消费端按订单号查重。
- Q：消息顺序重要吗？→ 单条订单的下单和关单有先后依赖；关单消息延迟 60 秒才到，正常情况下一定晚于下单消息，如果下单消费积压超过 60 秒，关单消费者查不到订单会抛异常重试，最终一致。
- Q：为什么关单用条件更新而不是先查再改？→ 支付和关单可能并发，先查再改有竞态；`UPDATE ... WHERE status = 1` 天然互斥，返回影响行数判断谁成功。
- Q：延迟队列消息堆积怎么办？→ 延迟队列只存待到期的关单消息，量级等于未支付订单数；堆积时先看消费者是否正常，必要时扩容消费者或缩短 TTL。