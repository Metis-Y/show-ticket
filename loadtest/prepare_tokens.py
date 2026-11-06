"""
压测数据准备：生成 N 个登录 token 写入 Redis，并刷新 tokens.csv。

后端鉴权只认 Redis 里的 `show-ticket:login:token:{token}` 这个 Hash
（见 RefreshTokenInterceptor），Hash 里的 id 存虚拟用户编号 1..N，
用来区分「一人一票」限购（tb_ticket_order 无外键，虚拟 id 可正常落库）。

token 每次被请求时自动续期 30 分钟（LOGIN_USER_TTL）。

默认在写入新 token 前，先删掉 Redis 里所有 `show-ticket:login:token:*`
旧 key（上一轮残留的压测 token 不会被带进新一轮）；不想删时加 --keep-old。

用法：
    python prepare_tokens.py                  # 默认 500 个，写 VM 的 Redis
    python prepare_tokens.py --count 100      # 少量场景（梯度加压用）
    python prepare_tokens.py --keep-old       # 保留已有 token，只追加新的一批

注意：token key 前缀与真实用户登录共用（同一套 Redis），执行会顺带踢掉
Redis 上所有已登录的真实用户会话——压测专用库没问题，共用环境慎用。

依赖：pip install redis
"""
import argparse
import os
import uuid

import redis

KEY_PREFIX = "show-ticket:login:token:"
# CSV 默认锚定脚本所在目录（loadtest/），不随执行时的当前目录漂移——
# grab.jmx 里读的固定是 loadtest/tokens.csv，写错位置会让压测全员 401。
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
# 初始 TTL：设长一点（2 小时），避免"造完 token 放一会儿就全 401"。
# 后端每次收到该 token 的请求会重新续期 30 分钟（LOGIN_USER_TTL）。
TTL_SECONDS = 60 * 120
# 删除旧 token 时的分批大小（SCAN + pipeline，避免一次命令太大）
FLUSH_BATCH = 500


def flush_old_tokens(r):
    """SCAN 出所有旧 token key 并分批 unlink，返回删除数量。"""
    removed = 0
    batch = []
    pipe = r.pipeline()
    for key in r.scan_iter(match=KEY_PREFIX + "*", count=FLUSH_BATCH):
        batch.append(key)
        removed += 1
        if len(batch) >= FLUSH_BATCH:
            pipe.delete(*batch)
            pipe.execute()
            batch = []
    if batch:
        pipe.delete(*batch)
        pipe.execute()
    return removed


def main():
    ap = argparse.ArgumentParser(description="生成压测用登录 token")
    ap.add_argument("--count", type=int, default=500, help="虚拟用户数（= tokens.csv 行数）")
    ap.add_argument("--host", default="192.168.100.130", help="Redis 主机（虚拟机）")
    ap.add_argument("--port", type=int, default=6379)
    ap.add_argument("--password", default="123")
    ap.add_argument("--csv", default="tokens.csv",
                    help="输出 CSV 路径（相对路径基于脚本所在 loadtest/；默认 loadtest/tokens.csv）")
    ap.add_argument("--keep-old", action="store_true",
                    help="保留 Redis 里已有 token（默认先全部删掉旧 token）")
    args = ap.parse_args()

    r = redis.Redis(host=args.host, port=args.port, password=args.password,
                    decode_responses=True, socket_connect_timeout=5)
    r.ping()  # 连不上直接抛异常，别静默产出半数据

    if not args.keep_old:
        removed = flush_old_tokens(r)
        print(f"[OK] 旧 token 已删除 {removed} 个（--keep-old 可跳过这步）")

    tokens = []
    pipe = r.pipeline()
    for i in range(1, args.count + 1):
        token = str(uuid.uuid4())
        tokens.append(token)
        pipe.hset(KEY_PREFIX + token, mapping={"id": str(i)})
        pipe.expire(KEY_PREFIX + token, TTL_SECONDS)
        if len(tokens) % 200 == 0:  # 分批提交，避免单次 pipeline 过大
            pipe.execute()
    pipe.execute()

    csv_path = args.csv if os.path.isabs(args.csv) else os.path.join(SCRIPT_DIR, args.csv)
    with open(csv_path, "w", encoding="utf-8", newline="") as f:
        f.write("\n".join(tokens) + "\n")

    print(f"[OK] {args.count} 个 token 已写入 Redis ({args.host}:{args.port})，CSV: {csv_path}")


if __name__ == "__main__":
    main()