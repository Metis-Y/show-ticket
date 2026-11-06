"""
轮间复位：订单、库存、一人一票占位、MQ 残留消息全部回到开测前状态。

复位的四处数据：
  1. MySQL  tb_ticket_order      清空（上轮订单表会累积记录）
  2. MySQL  tb_show_stock.stock  重置为设定值（原值已等于目标值时不算错误）
  3. Redis  show-ticket:ticket:stock:{showId}   库存 key 直接写满
  4. Redis  show-ticket:ticket:order:{showId}   一人一票占位集合清空
  5. RabbitMQ（--purge-mq 时）   延迟/超时/抢票/死信四个队列清空
（开售窗口 show-ticket:ticket:window:{showId} 不动，预热写过就一直有效）

内置等待：清表前默认 sleep 2 秒，让上一轮在途的异步落库先消费完，
避免复位后又被旧消息插进新行（--wait 0 可跳过）。

为什么建议 --purge-mq：上一轮发出的「60 秒延迟关单」消息在复位后到期时，
对应订单已被 TRUNCATE，消费端对空订单重试 3 次后进死信队列（
spring.rabbitmq default-requeue-rejected=false + max-attempts=3）。
清掉延迟队列就不会有这批噪音；死信队列也会随轮数累积，一并清掉更干净。

用法：
    python reset_round.py                    # 默认 show=1, stock=50, 等待 2s
    python reset_round.py --stock 100        # 换库存量
    python reset_round.py --purge-mq         # 顺带清 RabbitMQ 相关队列
    python reset_round.py --wait 0           # 不等在途消息，立即清表

依赖：pip install pymysql redis
"""
import argparse
import base64
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


def purge_mq_queues(args):
    """经 RabbitMQ HTTP 管理台清空队列。尽力而为：失败只警告，不影响其他复位步骤。"""
    auth = base64.b64encode(f"{args.mq_user}:{args.mq_password}".encode()).decode()
    vhost = urllib.parse.quote(args.mq_vhost.strip("/") or "/", safe="")
    for q in MQ_QUEUES:
        url = f"http://{args.mq_host}:{args.mq_port}/api/queues/{vhost}/{q}/contents"
        req = urllib.request.Request(url, method="DELETE")
        req.add_header("Authorization", f"Basic {auth}")
        try:
            urllib.request.urlopen(req, timeout=5)
            print(f"[OK] MQ 队列已清空: {q}")
        except urllib.error.HTTPError as e:
            # 404 = 队列还没被声明过（后端没消费/没跑过），可忽略
            print(f"[WARN] MQ 队列 {q} 清理失败 HTTP {e.code}（不存在可忽略）")
        except Exception as e:
            print(f"[WARN] MQ 清队列中断: {e}（管理台不可达？其余复位不受影响）")
            break


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
                    help="顺带经管理台清空 RabbitMQ 抢票相关队列（去上一轮延迟关单噪音）")
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

    # 1) MySQL：清订单表 + 重置 DB 库存
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

    # 2) Redis：库存写满 + 清一人一票占位
    r = redis.Redis(host=args.redis_host, port=args.redis_port,
                    password=args.redis_password, decode_responses=True)
    r.ping()
    r.set(REDIS_KEY_STOCK.format(args.show), str(args.stock))
    r.delete(REDIS_KEY_ORDER_SET.format(args.show))
    print(f"[OK] Redis: {REDIS_KEY_STOCK.format(args.show)}={args.stock}，一人一票占位已清空")

    # 3) RabbitMQ：清掉上一轮残留的延迟关单/死信（可选）
    if args.purge_mq:
        purge_mq_queues(args)

    print("[OK] 复位完成，可以开下一轮。")


if __name__ == "__main__":
    main()