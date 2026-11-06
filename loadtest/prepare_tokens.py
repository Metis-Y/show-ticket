"""
压测数据准备：生成 N 个登录 token 写入 Redis，并刷新 tokens.csv。

后端鉴权只认 Redis 里的 `show-ticket:login:token:{token}` 这个 Hash
（见 RefreshTokenInterceptor），Hash 里的 id 存虚拟用户编号 1..N，
用来区分「一人一票」限购（tb_ticket_order 无外键，虚拟 id 可正常落库）。

token 每次被请求时自动续期 30 分钟（LOGIN_USER_TTL）。

用法：
    python prepare_tokens.py                  # 默认 500 个，写 VM 的 Redis
    python prepare_tokens.py --count 100      # 少量场景（梯度加压用）

依赖：pip install redis
"""
import argparse
import uuid

import redis

KEY_PREFIX = "show-ticket:login:token:"
# 初始 TTL：设长一点（2 小时），避免"造完 token 放一会儿就全 401"。
# 后端每次收到该 token 的请求会重新续期 30 分钟（LOGIN_USER_TTL）。
TTL_SECONDS = 60 * 120


def main():
    ap = argparse.ArgumentParser(description="生成压测用登录 token")
    ap.add_argument("--count", type=int, default=500, help="虚拟用户数（= tokens.csv 行数）")
    ap.add_argument("--host", default="192.168.100.130", help="Redis 主机（虚拟机）")
    ap.add_argument("--port", type=int, default=6379)
    ap.add_argument("--password", default="123")
    ap.add_argument("--csv", default="tokens.csv", help="输出 CSV 路径（相对脚本目录）")
    args = ap.parse_args()

    r = redis.Redis(host=args.host, port=args.port, password=args.password,
                    decode_responses=True, socket_connect_timeout=5)
    r.ping()  # 连不上直接抛异常，别静默产出半数据

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

    csv_path = args.csv
    with open(csv_path, "w", encoding="utf-8", newline="") as f:
        f.write("\n".join(tokens) + "\n")

    print(f"[OK] {args.count} 个 token 已写入 Redis ({args.host}:{args.port})，CSV: {csv_path}")


if __name__ == "__main__":
    main()