"""
轮间复位：把「订单、库存、一人一票占位」全部回到开测前的满库存状态。

复位的三处数据：
  1. MySQL  tb_ticket_order      清空（上轮订单表会累积记录）
  2. MySQL  tb_show_stock.stock  重置为设定值（延迟关单会回补，但轮间直接置满更省事）
  3. Redis  show-ticket:ticket:stock:{showId}   库存 key 直接写满
  4. Redis  show-ticket:ticket:order:{showId}   一人一票占位集合清空
（开售窗口 show-ticket:ticket:window:{showId} 不动，预热写过就一直有效）

用法：
    python reset_round.py                    # 默认 show=1, stock=50
    python reset_round.py --stock 100        # 换库存量

注意：上一轮的异步落库正在写入时先等 1~2 秒再复位，否则会残留新行。
依赖：pip install pymysql redis
"""
import argparse

import pymysql
import redis

REDIS_KEY_STOCK = "show-ticket:ticket:stock:{}"
REDIS_KEY_ORDER_SET = "show-ticket:ticket:order:{}"


def main():
    ap = argparse.ArgumentParser(description="压测轮间复位")
    ap.add_argument("--show", type=int, default=1, help="演出 id")
    ap.add_argument("--stock", type=int, default=50, help="复位后的库存数")
    ap.add_argument("--mysql-host", default="127.0.0.1")
    ap.add_argument("--mysql-port", type=int, default=3306)
    ap.add_argument("--mysql-user", default="root")
    ap.add_argument("--mysql-password", default="123456")
    ap.add_argument("--mysql-db", default="show_ticket")
    ap.add_argument("--redis-host", default="192.168.100.130")
    ap.add_argument("--redis-port", type=int, default=6379)
    ap.add_argument("--redis-password", default="123")
    args = ap.parse_args()

    # 1) MySQL：清订单表 + 重置 DB 库存
    conn = pymysql.connect(host=args.mysql_host, port=args.mysql_port, user=args.mysql_user,
                           password=args.mysql_password, database=args.mysql_db)
    try:
        with conn.cursor() as cur:
            cur.execute("TRUNCATE TABLE tb_ticket_order")
            cur.execute("UPDATE tb_show_stock SET stock=%s WHERE show_id=%s",
                        (args.stock, args.show))
            if cur.rowcount != 1:
                print(f"[WARN] tb_show_stock 没有 show_id={args.show} 的记录，UPDATE 未生效")
        conn.commit()
        print("[OK] MySQL: tb_ticket_order 已清空，show_id=%s 库存=%s" % (args.show, args.stock))
    finally:
        conn.close()

    # 2) Redis：库存写满 + 清一人一票占位
    r = redis.Redis(host=args.redis_host, port=args.redis_port,
                    password=args.redis_password, decode_responses=True)
    r.ping()
    r.set(REDIS_KEY_STOCK.format(args.show), str(args.stock))
    r.delete(REDIS_KEY_ORDER_SET.format(args.show))
    print(f"[OK] Redis: {REDIS_KEY_STOCK.format(args.show)}={args.stock}，一人一票占位已清空")
    print("[OK] 复位完成，可以开下一轮。")


if __name__ == "__main__":
    main()