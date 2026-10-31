# 抢票接口压测（JMeter）

目标：用 **500 个不同用户并发抢 50 张票**，验证「不超卖、不重复、接口吞吐」。

## 文件说明

| 文件 | 作用 |
| --- | --- |
| `prepare_test_data.py` | 造 500 个测试用户，直接往 Redis 写登录 token，生成 `tokens.csv`；同时把库存重置为 50 |
| `grab-test.jmx` | JMeter 测试计划：500 线程、10 秒爬坡、每个线程读一行 token 调抢票接口 |
| `tokens.csv` | 由脚本生成，一行一个 token（**先运行 prepare 才会有**） |

## 前置条件

1. MySQL、Redis、RabbitMQ、后端（8081）全部启动
2. 本机装了 Python（脚本只依赖标准库）
3. JMeter 路径：`D:\develop\JMeter\apache-jmeter-5.6.3\apache-jmeter-5.6.3\bin\jmeter.bat`

## 步骤一：准备压测数据

在项目根目录 `D:\quan_zhan\show-ticket` 执行：

```bash
python loadtest/prepare_test_data.py prepare
```

默认参数已经对上你的环境（MySQL root/123456、Redis 192.168.100.130/123、
RabbitMQ admin/123、vhost show-ticket）。换环境时用参数覆盖，例如：

```bash
python loadtest/prepare_test_data.py prepare --redis-host 127.0.0.1 --count 1000
```

做了什么：删除旧测试数据 → 插入用户 1001~1500 → 重置 `tb_show_stock.stock=50` →
写 Redis `show-ticket:ticket:stock:1=50`、清空限购集合 → 为每个用户生成 UUID token，
写入 `show-ticket:login:token:{token}` 的 Hash（id/nickName/icon）→ 生成 `tokens.csv`。

> 为什么不用验证码登录 500 次：登录要读控制台验证码，没法自动化；后端鉴权只看
> Redis 里的 token Hash，所以直接写 Redis 等价于已登录。

## 步骤二：跑压测

### 正式压测（命令行，推荐）

```bat
cd /d D:\quan_zhan\show-ticket
D:\develop\JMeter\apache-jmeter-5.6.3\apache-jmeter-5.6.3\bin\jmeter.bat -n -t loadtest\grab-test.jmx -l loadtest\result.jtl -e -o loadtest\report
```

- `-n` 无界面模式（GUI 跑 500 线程会先把自己卡死）
- `-l` 原始结果文件
- `-e -o` 生成 HTML 报告，结束打开 `loadtest\report\index.html`

### 先小规模试跑（可选）

```bat
... jmeter.bat -n -t loadtest\grab-test.jmx -l loadtest\smoke.jtl -Jthreads=10
```

默认线程数是写死在 jmx 里的 500，想改就直接在 GUI 里改线程数后保存，或者复制一份 jmx。

### GUI 里看什么（第一次学 JMeter 用）

双击 `jmeter.bat` → 菜单 File → Open → 选 `grab-test.jmx`，左侧树：

```
show-ticket 抢票压测
└─ 500 并发抢票（Thread Group：线程数 500、Ramp-up 10、循环 1）
   ├─ 读取 tokens.csv（CSV Data Set Config，变量名 token）
   ├─ authorization 头（Header Manager，值 ${token}）
   ├─ POST /ticket-order/grab/1（HTTP Request，127.0.0.1:8081）
   │  └─ HTTP 200（Response Assertion）
   └─ 聚合报告（Aggregate Report）
```

- 先点绿色 ▶ 跑 5~10 个线程确认返回正常，再改回 500 用命令行跑
- 聚合报告看：`# Samples`、`Average`、`90% Line`、`99% Line`、`Throughput`、`Error %`

## 步骤三：结果怎么判定

压测结束后查四件事（SQL 和 Redis 都列出来）：

```sql
-- 1. 订单数应等于 50
SELECT COUNT(*) FROM tb_ticket_order WHERE show_id = 1;
-- 2. 库存应为 0，且不能为负
SELECT stock FROM tb_show_stock WHERE show_id = 1;
```

```bash
# 3. Redis 库存应为 0，限购集合大小应为 50
redis-cli -h 192.168.100.130 -a 123 GET show-ticket:ticket:stock:1
redis-cli -h 192.168.100.130 -a 123 SCARD show-ticket:ticket:order:1
```

4. HTML 报告里 Error% 应该只有「票已售罄」「每人限购一张」这类业务失败，
   **不能出现 500 或连接超时**（那些是系统错误，不是预期结果）。

合格线：**订单 50、DB 库存 0、Redis 库存 0、无负数、无 500**。

## 跑完清理 + 复跑

```bash
python loadtest/prepare_test_data.py clean     # 删测试用户/订单、恢复库存、清 token 和队列
python loadtest/prepare_test_data.py prepare   # 再跑一轮前重新准备
```

注意：若不清理，只为下一轮 prepare 也能跑，但残留的订单会让 `tb_ticket_order` 统计不准；
延迟关单消息（60 秒）还会回补库存，所以**压测后至少等 70 秒或先 clean 再统计**。

一个真实的坑：如果在 **60 秒延迟窗口内**执行了 prepare（订单被删除），延迟消息到期后
超时消费者会找不到订单而抛异常，重试 3 次耗尽后 **50 条消息全部进入死信队列**
`ticket.dead.queue`。这不是故障——恰恰证明「重试耗尽进死信」的兜底在真实工作。
测试数据是故意删的，直接清空死信队列即可（clean 脚本已包含）；管理台统计有几秒延迟，
purge 返回 204 后稍等片刻再看。

## 换场景怎么改

| 想改 | 改哪里 |
| --- | --- |
| 线程数 / 爬坡 | `grab-test.jmx` 里 `ThreadGroup.num_threads` / `ramp_time`，或 GUI 里改 |
| 抢哪场演出 | HTTP Request 的 path：`/ticket-order/grab/2`；prepare 加 `--show-id 2` |
| 库存多少 | prepare 加 `--stock 100` |
| 后端端口 | HTTP Request 的 `HTTPSampler.port` |
| 用户数量 | prepare 加 `--count 1000`（同时把线程数改 1000） |

## 简历上怎么写

跑完后记下三个数：**并发数、成功票数、接口吞吐（Throughput）与 P99**，例如：

> 500 并发抢 50 张票，订单数精确等于库存、无超卖；抢票接口 P99 xx ms，吞吐 xxx req/s。

对比实验（可选）：把 `grabTicket` 临时改回「同步扣库 + 事务落库」，同样并发下再跑一次，
就能写出「异步化后 QPS 从 X 提升到 Y」。
---

## 实测记录（2026-10-31 第一轮）

**环境**：本机 JDK 21 + 后端 8081 + 虚拟机 Redis/RabbitMQ；500 线程、Ramp-up 10s、循环 1 次。

**JMeter 报告**：

| 指标 | 数值 |
| --- | --- |
| Samples | 500 |
| Error%（HTTP 层） | 0.00% |
| APDEX | 1.000（T=500ms） |
| Average / Median | 8.86ms / 6ms |
| 90% / 95% / 99% | 9ms / 11ms / 128ms |
| Max | 128ms |
| Throughput | 51.39 req/s |

**业务正确性验证**（压测后查库）：

- 测试用户（id≥1001）订单数 = **50**，与库存完全一致，**零超卖、零重复**
- 订单创建时间 00:41:14 ~ 00:41:15（1 秒内全部异步落库完成）
- 60 秒后（00:42:14 ~ 00:42:15）延迟消息到期，50 单全部自动取消（status=4）
- 取消后 DB 库存回补至 50，Redis 限购占位清空

**结论**：抢票接口（纯 Redis + Lua）在 500 用户并发下 P99 = 128ms、零系统错误；
订单数精确等于库存；延迟关单与库存回补在真实压力下按预期工作。

**待改进**：Ramp-up 10s 使瞬时并发仅约 50，第二轮可用 Ramp-up 1s 冲峰值吞吐；
另可做「同步落库」对照实验，得到异步化前后的 QPS 对比。

---

## 实测记录 · 六轮压测与问题定位（2026-10-31）

### 六轮数据总览（500 线程、抢 show_id=1，除首轮外 Ramp-up=1s）

| 轮次 | 变量 | 样本 | HTTP 失败 | Average | P99 | 吞吐 | 业务订单 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Ramp-up 10s（基线） | 500 | 0 | 8.9ms | 128ms | 51/s | 50，零超卖 |
| 2 | Ramp-up 1s（洪峰） | 500 | 259 连接被拒 | 847ms | 1858ms | 265/s | 50，零超卖 |
| 3 | 堆固定 1G + GC 日志 | 500 | 73 连接被拒 | 982ms | 1309ms | 371/s | 50，零超卖 |
| 4 | 日志级别 debug→warn | 500 | 197 连接被拒 | 880ms | 1637ms | 297/s | 50，零超卖 |
| 5 | channel 缓存 25→200 | 500 | 258 连接被拒 | 728ms | 1600ms | 306/s | 50，零超卖 |
| 6 | 全配置 + JFR 录制 | 500 | 237 连接被拒 | 849ms | 1748ms | 282/s | 50，零超卖（已关单） |
| 7 | 默认 VM 配置复测（无固定堆/无监控） | 500 | 132 连接被拒 | 613ms | 1084ms | 456/s | 50，零超卖 |
| 8 | channel 缓存 200（A/B） | 500 | **0 连接被拒** | 198ms | 606ms | 493/s | 50，零超卖 |
| 10 | channel 开（A/B 复测） | 500 | **0 连接被拒** | 130ms | 492ms | 498/s | 50，零超卖 |
| 11 | channel 关（A/B 复测） | 500 | **0 连接被拒** | 131ms | 497ms | 492/s | 50，零超卖 |

每一轮的业务结果都正确：**订单数精确等于库存、零超卖、零重复**；轮 2~6 的订单在
60 秒后被延迟消息全部自动取消、库存回补（轮 1 后未等待，轮 2 起均验证）。

### 排查过程（每轮只验证一个假设）

| 假设 | 实验 | 结果 |
| --- | --- | --- |
| JVM 动态扩堆导致长 GC 停顿 | 固定 `-Xms1g -Xmx1g` + `-Xlog:gc`，复测 | 被拒 259→73，但未归零；GC 日志最大停顿仅 **29ms** → **排除** |
| 同步控制台日志串行化 | `com.showticket` 日志级别 debug→warn，复测 | 被拒不降反升（73→197），成功请求延迟不变 → **排除** |
| AMQP channel.open 同步 RPC 串行 | channel 缓存 25→200，复测 | 无变化 → **排除**（事后发现轮 3 起该配置已生效） |
| Redis 连接池排队 | 正确测量 Redis RTT：单连接 **0.47ms**、2117 ops/s；池 10 时 1500 条命令排队仅 71ms | **排除** |
| RabbitMQ 流控/告警 | 管理台 API 查节点 | mem/disk 告警均为 false → **排除** |

### 时间分布分析（核心证据）

对原始 jtl 统计每条请求的到达时间与完成时间：

- 被拒连接集中在洪峰开始阶段：轮 3/4 全部落在前 0.15s，轮 7 的 132 个中 96 个落在前 0.2s、
  其余散布全程——500 个 SYN 同时到达，accept 队列（默认 100）被打满
- 成功请求 0.5s 内全部到达，但**完成时间全部堆积在 1.2~1.7s**，中间一秒零完成——
  存在全局性排队/干扰，而非随机的慢
- 同期各组件实测都不慢（见下表），指向客户端与被测系统同机互抢 CPU/调度

### 进阶：JFR 录制（补充验证，可讲可不讲）

第六轮通过 `-XX:StartFlightRecording` 录制 212s，`jcmd <pid> JFR.dump` 导出后分析：

- **慢 Socket 读**：只有 235 次 ≥10ms 的读，其中 217 次来自 **localhost（JMeter 连接）**
  且每次只读到约 1 字节——即 Tomcat 在等 **JMeter 客户端写请求数据**，而不是自己在算
- 到 VM（Redis/AMQP）的慢读仅 18 次
- **响应写**：0 次慢写——服务端回包不慢
- **锁竞争**：最大 65.9ms（类加载锁），累计摊到每线程 ~150ms，不足以造成 900ms
- **hot-methods**：CPU 几乎空闲（最热方法为 RabbitMQ 内部定时器，46 个采样）

### 最终结论

1. 服务端各环节（Redis/AMQP/GC/锁/CPU）在洪峰下都足够快，**瓶颈不在后端代码**；
2. 被拒连接是 500 个 SYN 在 <0.15s 内同时到达、accept 队列（默认 100）瞬间打满所致；
3. 成功请求 ~900ms 的耗时主要是**客户端因素**：JMeter 500 线程与后端/MySQL/IDEA
   挤在同一台笔记本上，客户端线程写请求/读响应被调度延迟——服务端等的是客户端；
4. **洪峰能力最终得到复现**：轮 8/10/11 连续三轮 500 请求 1 秒内打入，**零连接被拒**、
   吞吐 492~498 req/s、P99 492~606ms（随 JIT 预热变好）。中间轮次（2~7）的被拒
   （73~259 个，波动大）未复现、归因未定——同机自压测噪声显著，属调度/环境干扰。
   数字仍以轮间对比口径使用；绝对容量需分机部署压测。

### 教训与沉淀

- 一次只改一个变量；每轮保留原始数据（jtl），结论必须能复现
- 假设被证伪和被证实一样有价值：GC/日志/channel/Redis 四个假设的排除都有实验背书
- 压测系统自身会成为瓶颈：同机自压的数字只能用于「对比」，不能当「绝对容量」
