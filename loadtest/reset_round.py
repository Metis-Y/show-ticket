"""
轮间复位：订单、库存、一人一票占位、MQ 残留消息全部回到开测前状态。

复位的四处数据：
  1. MySQL  tb_ticket_order      清空（上轮订单表会累积记录）
  2. MySQL  tb_show_stock.stock  重置为设定值（原值已等于目标值时不算错误）
  3. Redis  show-ticket:ticket:stock:{showId}   库存 key 直接写满
  4. Redis  show-ticket:ticket:order:{showId}   一人一票占位集合清空
  5. RabbitMQ（--purge-mq 时）   延迟/超时/抢票/死信四个队列清空
（开售窗口 show-ticket:ticket:window:{showId} 不动，预热写过就一直有效）

为什么建议 --purge-mq，以及"清了还在重试"是怎么回事：
  · 上一轮发出的「60 秒延迟关单」消息到期后进 ticket.timeout.queue；
    如果此时订单已被 TRUNCATE，消费者找不到订单 → 抛异常 → Spring 重试
    （max-attempts=3，退避 1s/2s）→ 重试耗尽后进死信队列。
  · 管理台的 purge 只能清「还没投递出去」的消息（ready）；
    已经投递给消费者、正在重试的消息是 unacked，purge 清不掉，只能等它跑完 3 次重试。
    所以"清了还有重试日志"不是清理失败，而是那批消息已经不在队列里了。

本脚本的复位顺序（--purge-mq 时）：
  1) 等 2 秒，让上一轮在途的异步落库收尾
  2) 先 purge 一次：清掉延迟队列里还没到期的、超时队列里排队中的消息
  3) 等 unacked 排空（--drain-timeout 上限）：此时订单还在，
     重试中的关单消息能正常处理并 ack，不会进死信
  4) 清订单表 + 复位 DB/Redis 库存与占位
  5) 最后再 purge 一次：清掉 2~3 期间可能新进死信的消息

来不及等排空时：先停后端再复位——连接关闭后 unacked 会重回 ready，再清就干净了。
若不希望复位后出现这类重试噪音，也可以把 cancelTimeoutOrder 里「订单不存在」
的处理改为记日志后直接 ack（代价是放弃"延迟消息先于下单消息到达"这一极小概率的兜底重试）。

用法：
    python reset_round.py                    # 默认 show=1, stock=50, 等待 2s
    python reset_round.py --stock 100        # 换库存量
    python reset_round.py --purge-mq         # 顺带清 RabbitMQ 并等待重试排空
    python reset_round.py --purge-mq --drain-timeout 120
    python reset_round.py --wait 0           # 不等在途消息，立即清表

依赖：pip install pymysql redis
"""
import argparse
import base64
import json
import time
import urllib.error
import urllib.parse
import urllib.request

import pymysql
import redis

REDIS_KEY_STOCK = "show-ticket:ticket:stock:{}"
REDIS_KEY_ORDER_SET = "show-ticket:ticket:order:{}"

# 与后端 RabbitMqConfig 里的队列名一致
MQ_QUEUES = (
    "ticket.delay.queue",    # 延迟关单消息（60 秒 TTL，复位后到期会变噪音）
    "ticket.timeout.queue",  # 延迟到期后的超时关单队列
    "ticket.grab.queue",     # 异步下单消息
    "ticket.dead.queue",     # 重试耗尽的死信
)


def _mgmt_url(args, suffix):
    vhost = urllib.parse.quote(args.mq_vhost.strip("/") or "/", safe="")
    return f"http://{args.mq_host}:{args.mq_port}/api/queues/{vhost}/{suffix}"


def _mgmt_request(args, suffix, method):
    auth = base64.b64encode(f"{args.mq_user}:{args.mq_password}".encode()).decode()
    req = urllib.request.Request(_mgmt_url(args, suffix), method=method)
    req.add_header("Authorization", f"Basic {auth}")
    return urllib.request.urlopen(req, timeout=5)


def purge_mq_queues(args):
    """经 RabbitMQ HTTP 管理台清空队列。

    只对 ready 消息有效：已经投递给消费者、正在重试的消息是 unacked，
    purge 清不掉（那批消息已经不在队列里，只能等它重试结束）。
    """
    ok = True
    for q in MQ_QUEUES:
        try:
            _mgmt_request(args, f"{q}/contents", "DELETE")
            print(f"[OK] MQ 队列已清空: {q}")
        except urllib.error.HTTPError as e:
            # 404 = 队列还没被声明过（后端没消费/没跑过），可忽略
            print(f"[WARN] MQ 队列 {q} 清理失败 HTTP {e.code}（不存在可忽略）")
        except Exception as e:
            print(f"[WARN] MQ 清队列中断: {e}（管理台不可达？其余复位不受影响）")
            ok = False
            break
    return ok


def queue_stats(args):
    """返回 {queue: (ready, unacked)}；管理台不可达时返回 None。"""
    stats = {}
    for q in MQ_QUEUES:
        try:
            with _mgmt_request(args, q, "GET") as resp:
                d = json.load(resp)
            stats[q] = (d.get("messages_ready", 0), d.get("messages_unacknowledged", 0))
        except urllib.error.HTTPError as e:
            if e.code == 404:      # 队列不存在 = 空
                stats[q] = (0, 0)
            else:
                return None
        except Exception:
            return None
    return stats


def drain_mq(args):
    """轮询等待重试中的消息（unacked）跑完并 ack。

    此时订单还没清，关单消息能正常处理，所以通常很快静默、不会进死信。
    """
    print(f"[..] 等待重试中的消息排空（上限 {args.drain_timeout:.0f}s）...")
    deadline = time.time() + args.drain_timeout
    last = None
    while time.time() < deadline:
        stats = queue_stats(args)
        if stats is None:
            print("[WARN] 管理台不可达，跳过排空检查")
            return False
        busy = {q: s for q, s in stats.items() if s[0] or s[1]}
        if not busy:
            print("[OK] MQ 已无残留消息（ready 与 unacked 都为空）")
            return True
        info = ", ".join(f"{q}: 待消费 {r} / 重试中 {u}" for q, (r, u) in busy.items())
        if info != last:           # 只在数字变化时打印，避免刷屏
            print(f"[..] {info}")
            last = info
        time.sleep(2)
    print("[WARN] 等待超时，仍有消息在重试：可加大 --drain-timeout，"
          "或先停后端再复位（unacked 会随连接关闭重回 ready）")
    return False


def main():
    ap = argparse.ArgumentParser(description="压测轮间复位")
    ap.add_argument("--show", type=int, default=1, help="演出 id")
    ap.add_argument("--stock", type=int, default=50, help="复位后的库存数")
    ap.add_argument("--wait", type=float, default=2.0,
                    help="清表前等待秒数，让上一轮异步落库收尾（0=不等）")
    ap.add_argument("--mysql-host", default="127.0.0.1")
    ap.add_argument("--mysql-port", type=int, default=3306)
    ap.add_argument("--mysql-user", default="root")
    ap.add_argument("--mysql-password", default="123456")
    ap.add_argument("--mysql-db", default="show_ticket")
    ap.add_argument("--redis-host", default="192.168.100.130")
    ap.add_argument("--redis-port", type=int, default=6379)
    ap.add_argument("--redis-password", default="123")
    ap.add_argument("--purge-mq", action="store_true",
                    help="顺带清空 RabbitMQ 抢票相关队列，并等待重试消息排空")
    ap.add_argument("--drain-timeout", type=float, default=90,
                    help="等待重试中的消息排空的最长秒数（--purge-mq 时生效）")
    ap.add_argument("--mq-host", default="192.168.100.130")
    ap.add_argument("--mq-port", type=int, default=15672, help="RabbitMQ 管理台端口")
    ap.add_argument("--mq-user", default="admin")
    ap.add_argument("--mq-password", default="123")
    ap.add_argument("--mq-vhost", default="show-ticket")
    args = ap.parse_args()

    # 0) 等上一轮异步落库收尾，再清表
    if args.wait > 0:
        print(f"[..] 等待 {args.wait}s，让上一轮在途消息消费完...")
        time.sleep(args.wait)

    # 1) 先清一次 MQ：清掉延迟队列里还没到期的、超时队列里排队中的消息
    if args.purge_mq:
        purge_mq_queues(args)
        # 2) 等 unacked 排空：此时订单还在，重试中的关单消息能成功 ack
        drain_mq(args)

    # 3) MySQL：清订单表 + 重置 DB 库存
    conn = pymysql.connect(host=args.mysql_host, port=args.mysql_port, user=args.mysql_user,
                           password=args.mysql_password, database=args.mysql_db)
    try:
        with conn.cursor() as cur:
            # 先查行在不在（UPDATE 行数受 affected-rows 语义影响，
            # 库存本来就没变时会是 0，不能拿它判断记录是否存在）
            cur.execute("SELECT stock FROM tb_show_stock WHERE show_id=%s", (args.show,))
            row = cur.fetchone()
            if row is None:
                print(f"[WARN] tb_show_stock 没有 show_id={args.show} 的记录，DB 库存未复位（Redis 仍会写满）")
            else:
                cur.execute("UPDATE tb_show_stock SET stock=%s WHERE show_id=%s",
                            (args.stock, args.show))
                print(f"[OK] MySQL: tb_show_stock 库存 {row[0]} -> {args.stock}")
            cur.execute("TRUNCATE TABLE tb_ticket_order")
        conn.commit()
        print(f"[OK] MySQL: tb_ticket_order 已清空（show_id={args.show}）")
    finally:
        conn.close()

    # 4) Redis：库存写满 + 清一人一票占位
    r = redis.Redis(host=args.redis_host, port=args.redis_port,
                    password=args.redis_password, decode_responses=True)
    r.ping()
    r.set(REDIS_KEY_STOCK.format(args.show), str(args.stock))
    r.delete(REDIS_KEY_ORDER_SET.format(args.show))
    print(f"[OK] Redis: {REDIS_KEY_STOCK.format(args.show)}={args.stock}，一人一票占位已清空")

    # 5) 最后再清一次：把 1~4 期间可能新进死信的消息一并清掉
    if args.purge_mq:
        purge_mq_queues(args)

    print("[OK] 复位完成，可以开下一轮。")


if __name__ == "__main__":
    main()
