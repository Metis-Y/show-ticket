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