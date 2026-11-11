# 抢票接口压测（/ticket-order/grab/{showId}）

N 个不同用户抢 50 张票。**注意：重启后端后的第一轮是冷启动，数字要丢弃**——原因与实测见 `docs/07-压测实战-冷启动与稳态.md`。

## 文件

| 文件 | 作用 |
| --- | --- |
| `grab.jmx` | JMeter 压测计划（GUI 直跑版，含 500 / 1000 两个档位，右键线程组禁用/启用切换） |
| `prepare_tokens.py` | 造 N 个登录 token 写 Redis，并刷新 `tokens.csv` |
| `reset_round.py` | 轮间复位：清订单表、重置库存、清一人一票占位；`--purge-mq` 顺带清 MQ 队列并等待重试消息排空 |
| `tokens.csv` | 每行一个 token（无表头），由 prepare 脚本生成；1500 行覆盖两档 |
| `result_tree.jtl` | 「查看结果树」自动写入的结果文件（追加式，开跑前删掉即清零） |

## 前置条件

1. 后端已启动（http://localhost:8081），MySQL（127.0.0.1:3306）、Redis（192.168.100.130:6379）、RabbitMQ（192.168.100.130:5672）均可用
2. 票档 show_id=1 库存 50、开售窗口有效（启动预热写入 Redis）

## 每轮标准流程

```bash
cd D:\quan_zhan\show-ticket

# 1) 造 token（初始有效 2 小时，每次请求自动续期 30 分钟；压测前必跑，1500 覆盖两档）
#    会自动删掉 Redis 里上一轮的旧 token（想保留加 --keep-old）
python loadtest/prepare_tokens.py --count 1500

# 2) 复位数据（库存满 50、订单表干净；--purge-mq 清掉上一轮延迟关单/死信残留）
python loadtest/reset_round.py --purge-mq
```

> **冷启动**：后端刚启动后的第一轮会明显偏慢（avg ~100ms 级，P99 400ms+），这是 JVM/连接池预热，
> 不是接口问题——**首轮跑完直接丢弃，从第二轮开始记数**；详见 `docs/07-压测实战-冷启动与稳态.md`。

3. 双击 `D:\develop\JMeter\apache-jmeter-5.6.3\apache-jmeter-5.6.3\bin\jmeter.bat` 打开 GUI：
   - File → Open 打开 `loadtest\grab.jmx`
   - **选档位**：左侧树里右键线程组 → 「禁用 / 启用」（灰色=禁用=不跑）。默认：500 档启用、1000 档禁用；跑千并发就反过来
   - 点绿色 ▶ 启动，结果看「聚合报告」标签页；「查看结果树」已开启并写入 `result_tree.jtl`，可看每个请求的 token 与完整响应
4. 命令行跑法（可选，用文件里保存的当前数值）：
   `jmeter.bat -n -t loadtest/grab.jmx -l loadtest/result12.jtl`

## 档位切换

一个文件两个线程组，**二选一，不要同时启用**：

| 档位 | 线程数 | 场景 | 切换方式 |
| --- | --- | --- | --- |
| 500 档 | 500 | 基线（默认启用） | 右键线程组 → 禁用/启用 |
| 1000 档 | 1000 | 千并发（默认禁用） | 同上（堆内存已调到 3g，见注意事项） |

## GUI 里改哪些框

| 场景 | 位置 | 改成 |
| --- | --- | --- |
| 洪峰压测 | 线程组 → Ramp-Up 时间(秒) | 1（基线用 10） |
| 多轮 | 线程组 → 循环次数 | 2 以上 |
| 换演出 | HTTP 请求 → 路径 | `/ticket-order/grab/其他id` |
| 换后端 | HTTP 请求 → 服务器名称/IP、端口号 | 分机 IP 等 |

## 口径与注意事项

- 业务拒绝（票已售罄 / 每人限购 / 未开票）= **HTTP 200 + success:false**，不计入 JMeter Error%；Error% 只统计系统级失败（非 2xx / 超时 / 连接失败）
- **冷/热要分开**：重启后端后的第一轮是冷启动（avg ~100ms 级），只报它等于报错数据；稳态从第二轮起算（avg 4~5ms、P99 10~24ms）
- **报告别累积**：GUI 聚合报告跨轮会累加样本（出现过 3 轮混成 1500 样本、吞吐被轮间空档稀释成 3.87/s），每轮先点「清除」或改用命令行 `-l result_n.jtl` 每轮新文件
- 每轮 50 笔订单 1 秒内异步落库；**60 秒未支付自动关单回补库存**（`app.order.timeout-ms`），不跑复位脚本也可以隔 60 秒开下一轮
- 1000 线程的堆内存已按 `-Xms1g -Xmx3g -XX:MaxMetaspaceSize=512m` 改好（`D:\develop\JMeter\...\bin\jmeter.bat` 的 `set HEAP=` 行），默认 1G 会 OOM；若跑千并发仍 OOM 再加到 4g
- 同机自压测（JMeter 与被测系统一台机器）的数字只用于轮间对比，不能当后端绝对容量——洪峰轮的吞吐/P99 会被客户端调度干扰污染