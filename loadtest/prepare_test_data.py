#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
演出抢票压测数据准备 / 清理脚本（仅用 Python 标准库）

用法（在项目根目录执行）：
    python loadtest/prepare_test_data.py prepare     # 造 500 个测试用户 + 登录 token + tokens.csv，并重置库存
    python loadtest/prepare_test_data.py clean       # 回滚：删测试订单/用户、恢复库存、清 Redis token、清空 MQ 队列

可选参数（都有默认值，按你的环境改）：
    --mysql "D:/java_code/Mysql/Mysql8.43/bin/mysql.exe"
    --db-host 127.0.0.1 --db-port 3306 --db-user root --db-pass 123456 --db-name show_ticket
    --redis-host 192.168.100.130 --redis-port 6379 --redis-pass 123
    --mq-host 192.168.100.130 --mq-user admin --mq-pass 123 --mq-vhost show-ticket
    --count 500 --show-id 1 --stock 50
"""

import argparse
import os
import socket
import subprocess
import sys
import time
import uuid

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CSV_PATH = os.path.join(BASE_DIR, "tokens.csv")
USER_ID_START = 1001


def build_parser():
    p = argparse.ArgumentParser()
    p.add_argument("action", choices=["prepare", "clean"])
    p.add_argument("--mysql", default=r"D:/java_code/Mysql/Mysql8.43/bin/mysql.exe")
    p.add_argument("--db-host", default="127.0.0.1")
    p.add_argument("--db-port", default="3306")
    p.add_argument("--db-user", default="root")
    p.add_argument("--db-pass", default="123456")
    p.add_argument("--db-name", default="show_ticket")
    p.add_argument("--redis-host", default="192.168.100.130")
    p.add_argument("--redis-port", type=int, default=6379)
    p.add_argument("--redis-pass", default="123")
    p.add_argument("--mq-host", default="192.168.100.130")
    p.add_argument("--mq-user", default="admin")
    p.add_argument("--mq-pass", default="123")
    p.add_argument("--mq-vhost", default="show-ticket")
    p.add_argument("--count", type=int, default=500)
    p.add_argument("--show-id", type=int, default=1)
    p.add_argument("--stock", type=int, default=50)
    return p


def run_sql(args, sql):
    cmd = [
        args.mysql,
        "-h" + args.db_host, "-P" + args.db_port,
        "-u" + args.db_user, "-p" + args.db_pass,
        "--default-character-set=utf8mb4", args.db_name,
    ]
    proc = subprocess.run(cmd, input=sql.encode("utf-8"), capture_output=True)
    if proc.returncode != 0:
        raise SystemExit("MySQL 执行失败：\n" + proc.stderr.decode("utf-8", "replace"))


class Redis:
    def __init__(self, host, port, password):
        self.sock = socket.create_connection((host, port), 5)
        self.sock.settimeout(5)
        if password:
            self.cmd("AUTH", password)

    def cmd(self, *parts):
        payload = ("*%d\r\n" % len(parts)).encode()
        for part in parts:
            data = str(part).encode()
            payload += b"$%d\r\n%s\r\n" % (len(data), data)
        self.sock.sendall(payload)
        return self._read_reply()

    def _read_reply(self):
        first = self.sock.recv(1)
        if not first:
            raise SystemExit("Redis 连接已断开")
        line = self._read_line()
        if first == b"+":
            return line.decode()
        if first == b"-":
            raise SystemExit("Redis 返回错误：" + line.decode())
        if first == b":":
            return int(line)
        if first == b"$":
            size = int(line)
            if size < 0:
                return None
            data = b""
            while len(data) < size + 2:
                data += self.sock.recv(size + 2 - len(data))
            return data[:-2].decode("utf-8", "replace")
        if first == b"*":
            count = int(line)
            return [self._read_reply() for _ in range(count)]
        raise SystemExit("无法解析的 Redis 响应: " + repr(first))

    def _read_line(self):
        data = b""
        while not data.endswith(b"\r\n"):
            data += self.sock.recv(1)
        return data[:-2]

    def close(self):
        self.sock.close()


def prepare(args):
    user_ids = list(range(USER_ID_START, USER_ID_START + args.count))
    id_min, id_max = user_ids[0], user_ids[-1]

    print("[1/5] 清理旧测试数据 ...")
    run_sql(args, f"""
DELETE FROM tb_ticket_order WHERE user_id BETWEEN {id_min} AND {id_max};
DELETE FROM tb_user_info WHERE user_id BETWEEN {id_min} AND {id_max};
DELETE FROM tb_user WHERE id BETWEEN {id_min} AND {id_max};
""")

    print("[2/5] 插入测试用户 ...")
    rows_user, rows_info = [], []
    for i, uid in enumerate(user_ids):
        phone = "139%08d" % i
        rows_user.append(f"({uid},'{phone}','tester{uid}','')")
        rows_info.append(f"({uid},'测试','压测账号',0,0,0,0,0)")
    run_sql(args, """
INSERT INTO tb_user (id, phone, nick_name, icon) VALUES
""" + ",".join(rows_user) + """;
INSERT INTO tb_user_info (user_id, city, introduce, fans, followee, gender, credits, level) VALUES
""" + ",".join(rows_info) + ";")

    print("[3/5] 重置库存 ...")
    run_sql(args, f"UPDATE tb_show_stock SET stock = {args.stock} WHERE show_id = {args.show_id};")

    redis_conn = Redis(args.redis_host, args.redis_port, args.redis_pass)
    redis_conn.cmd("SET", "show-ticket:ticket:stock:%d" % args.show_id, args.stock)
    redis_conn.cmd("DEL", "show-ticket:ticket:order:%d" % args.show_id)

    print("[4/5] 生成登录 token 并写入 Redis ...")
    tokens = []
    for i, uid in enumerate(user_ids):
        token = str(uuid.uuid4())
        tokens.append(token)
        redis_conn.cmd("HSET", "show-ticket:login:token:" + token,
                       "id", uid, "nickName", "tester%d" % uid, "icon", "")
        redis_conn.cmd("EXPIRE", "show-ticket:login:token:" + token, 3600)
    redis_conn.close()

    print("[5/5] 写 tokens.csv ...")
    with open(CSV_PATH, "w", encoding="utf-8", newline="") as f:
        for token in tokens:
            f.write(token + "\n")

    print("\n准备完成：")
    print("  测试用户 %d ~ %d（密码：无，仅 token 登录）" % (id_min, id_max))
    print("  show_id=%d 库存=%d" % (args.show_id, args.stock))
    print("  token 文件：%s" % CSV_PATH)
    print("  下一步：jmeter -n -t loadtest/grab-test.jmx -l loadtest/result.jtl -e -o loadtest/report")


def clean(args):
    user_ids = list(range(USER_ID_START, USER_ID_START + args.count))
    id_min, id_max = user_ids[0], user_ids[-1]

    print("[1/4] 删除测试订单和用户 ...")
    run_sql(args, f"""
DELETE FROM tb_ticket_order WHERE user_id BETWEEN {id_min} AND {id_max};
DELETE FROM tb_user_info WHERE user_id BETWEEN {id_min} AND {id_max};
DELETE FROM tb_user WHERE id BETWEEN {id_min} AND {id_max};
""")

    print("[2/4] 恢复库存 ...")
    run_sql(args, f"UPDATE tb_show_stock SET stock = {args.stock} WHERE show_id = {args.show_id};")

    print("[3/4] 清理 Redis（库存 / 限购集合 / token）...")
    redis_conn = Redis(args.redis_host, args.redis_port, args.redis_pass)
    redis_conn.cmd("SET", "show-ticket:ticket:stock:%d" % args.show_id, args.stock)
    redis_conn.cmd("DEL", "show-ticket:ticket:order:%d" % args.show_id)
    if os.path.exists(CSV_PATH):
        with open(CSV_PATH, encoding="utf-8") as f:
            for line in f:
                token = line.strip()
                if token:
                    redis_conn.cmd("DEL", "show-ticket:login:token:" + token)
    redis_conn.close()

    print("[4/4] 清空压测期间产生的 MQ 队列消息（失败可忽略）...")
    try:
        import base64
        import urllib.request
        auth = base64.b64encode(("%s:%s" % (args.mq_user, args.mq_pass)).encode()).decode()
        for queue in ("ticket.grab.queue", "ticket.delay.queue", "ticket.timeout.queue"):
            url = "http://%s:15672/api/queues/%s/%s/contents" % (args.mq_host, args.mq_vhost, queue)
            req = urllib.request.Request(url, method="DELETE")
            req.add_header("Authorization", "Basic " + auth)
            try:
                urllib.request.urlopen(req, timeout=5)
                print("  已清空", queue)
            except Exception as e:
                print("  跳过 %s（%s）" % (queue, e))
    except Exception as e:
        print("  跳过队列清理：", e)

    print("\n清理完成。")


def main():
    args = build_parser().parse_args()
    if args.action == "prepare":
        prepare(args)
    else:
        clean(args)


if __name__ == "__main__":
    main()