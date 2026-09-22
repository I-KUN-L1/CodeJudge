#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
压测账号池播种：批量创建学员账号并生成 JMeter 用的 users.csv。

为什么必须要有独立账号池：
  库里的种子账号只有 5 个（13900000001~00005）。若 100 并发共用这 5 个账号，
  会集中撞在同一批行上 —— MySQL 行锁争用、Redis 用户维度 key 冲突、
  「我的提交」分页命中同一份数据，压出来的数字反映的是锁竞争而不是系统容量。

幂等设计：
  · 先尝试登录。能登录 → 该账号已存在，直接复用（不重复注册，避免占用手机号）。
  · 登录报「用户不存在」→ 不带任何回调地 POST /students/register。
  这样脚本可以反复执行，不会因为「手机号已注册」而失败。

⚠️ 登录限流（必须知道）：
  网关对 POST /accounts/login 配了防爆破令牌桶（`login-rate-limit` 路由，
  key = `rate:login:{ip}:anon`）。默认 2 req/s、突发 5。**本脚本从单机跑，
  所有请求共用同一个令牌桶**，连续登录必然撞 HTTP 429 —— 而 429 响应体不是 JSON，
  直接抛错会得到「30 个账号全部失败」这种误导性结论（账号其实早就在库里）。
  因此这里对 429 做**退避重试**：播种是低频批量运维操作，不该被防爆破误伤。
  若想缩短耗时，可先放宽网关限流再播种：
      GW_LOGIN_RATE_REPLENISH=500 GW_LOGIN_RATE_BURST=1000 启动 judge-gateway

用法：
    python perf-test/seed-users.py --count 120
    python perf-test/seed-users.py --count 120 --phone-prefix 1390001 --start 1000

退出码：0 成功；非 0 表示有账号既登录不上也注册不了（此时不应开始压测）。
"""
import argparse
import csv
import os
import pathlib
import sys
import time

try:
    import requests
except ImportError:
    print("需要 requests：pip install requests", file=sys.stderr)
    sys.exit(2)

HERE = pathlib.Path(__file__).parent
CSV_PATH = HERE / "csv" / "users.csv"

GATEWAY = os.environ.get("CJ_PERF_GATEWAY", "http://127.0.0.1:9080")
PASSWORD = os.environ.get("CJ_PERF_PASS", "123456")

# 撞限流时的退避重试参数
RL_ATTEMPTS = int(os.environ.get("CJ_PERF_RL_ATTEMPTS", "10"))
RL_SLEEP = float(os.environ.get("CJ_PERF_RL_SLEEP", "0.7"))

# 三种结果，调用方必须区别对待：
#   "ok"        成功
#   "ratelimit" 被网关令牌桶拦下（HTTP 429）—— **不是业务失败，必须重试**
#   "fail"      真正的业务失败（密码错、手机号已存在、参数非法 …）
OK, RATELIMIT, FAIL = "ok", "ratelimit", "fail"


def _post(session, path, payload):
    """统一 POST：把 HTTP 429 单独识别出来，其余按 JSON 业务码判定。"""
    try:
        r = session.post(f"{GATEWAY}{path}", json=payload, timeout=10)
    except Exception as e:                       # 网关没起 / 网络不通
        return FAIL, f"连接失败：{e.__class__.__name__}: {e}"
    if r.status_code == 429:
        # 关键：429 的响应体不是业务 JSON，若不单独处理会被误报成「非 JSON 响应」，
        # 让调用方以为账号有问题，实际只是被限流。
        return RATELIMIT, "HTTP 429（网关登录限流）"
    try:
        body = r.json()
    except Exception:
        return FAIL, f"非 JSON 响应（HTTP {r.status_code}）"
    if body.get("code") == 200:
        return OK, "ok"
    return FAIL, str(body.get("message") or body.get("msg") or body)


def _with_backoff(fn, *args):
    """对 ratelimit 退避重试；业务失败立即返回，不做无谓重试。"""
    status, reason = RATELIMIT, "未执行"
    for attempt in range(RL_ATTEMPTS):
        status, reason = fn(*args)
        if status != RATELIMIT:
            return status, reason
        time.sleep(RL_SLEEP * (attempt + 1))     # 线性退避：0.7s, 1.4s, 2.1s …
    return status, f"{reason}（已退避重试 {RL_ATTEMPTS} 次仍被限流，请放宽网关限流或调大 CJ_PERF_RL_ATTEMPTS）"


def login(session, phone):
    return _with_backoff(_post, session, "/accounts/login",
                         {"cellPhone": phone, "password": PASSWORD})


def register(session, phone, name):
    return _with_backoff(_post, session, "/students/register",
                         {"cellPhone": phone, "password": PASSWORD,
                          "name": name, "username": f"load{phone[-6:]}"})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--count", type=int, default=120, help="生成账号数（默认 120，> 最大并发数）")
    ap.add_argument("--phone-prefix", default="139",
                    help="手机号前缀（≤9 位，默认 139）；与序号拼成**恰好 11 位**")
    ap.add_argument("--start", type=int, default=50000000,
                    help="起始序号（默认 50000000，避开 1390000000x 的种子账号段）")
    args = ap.parse_args()

    # ⚠ 手机号必须恰好 11 位且以 1 开头，否则后端 `^1\d{10}$` 校验直接 400。
    #   早期版本用 f"{prefix}{seq:06d}"[-11:] 拼接，前缀稍长就会被截掉开头的 "1"，
    #   产出 "90001001000" 这种非法号码，且**截断是静默的** —— 表现为「30 个账号全部注册失败」
    #   而看不到真正原因。这里改为显式补齐位数 + 前置断言。
    width = 11 - len(args.phone_prefix)
    if not (1 <= width <= 10):
        print(f"[FATAL] --phone-prefix 长度非法：需 1~10 位，当前 {len(args.phone_prefix)} 位", file=sys.stderr)
        return 2
    if args.start + args.count - 1 >= 10 ** width:
        print(f"[FATAL] 序号超出 {width} 位容量（最大 {10 ** width - 1}），请调大 --phone-prefix 或调小 --count",
              file=sys.stderr)
        return 2

    session = requests.Session()

    # 前置检查：网关不通就没必要往下走
    try:
        session.get(f"{GATEWAY}/actuator/health", timeout=5)
    except Exception:
        print(f"[FATAL] 网关不可达：{GATEWAY}\n"
              f"        先启动后端：python scripts/dev-start-backend.py --wait", file=sys.stderr)
        return 2

    CSV_PATH.parent.mkdir(parents=True, exist_ok=True)
    rows, reused, created, failed, throttled = [], 0, 0, 0, 0

    first_fail = None
    for i in range(args.count):
        seq = args.start + i
        phone = f"{args.phone_prefix}{seq:0{width}d}"       # 前缀 + 补齐序号 = 恰好 11 位
        assert len(phone) == 11, f"内部错误：生成手机号位数异常 {phone}"
        name = f"压测学员{seq}"

        st, reason = login(session, phone)
        if st == RATELIMIT:                     # 退避重试后仍被限流 —— 与业务无关，单独计数
            throttled += 1
            if first_fail is None:
                first_fail = f"{phone} 登录被限流（{reason}）"
            continue
        if st == OK:
            reused += 1
        else:
            st2, reason2 = register(session, phone, name)
            if st2 == OK:
                created += 1
            elif st2 == RATELIMIT:
                throttled += 1
                if first_fail is None:
                    first_fail = f"{phone} 注册被限流（{reason2}）"
                continue
            else:
                failed += 1
                if first_fail is None:
                    first_fail = f"{phone} 登录失败({reason}) 且注册失败({reason2})"
                continue
        rows.append((phone, PASSWORD))

    with CSV_PATH.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["cellPhone", "password"])              # 表头：JMeter 侧 ignoreFirstLine=true
        w.writerows(rows)

    print(f"账号池写入 {CSV_PATH}")
    print(f"  总数={len(rows)}  复用={reused}  新建={created}  失败={failed}  限流={throttled}")
    if failed or throttled:
        # 只回显**第一条**失败原因：同一根因（如手机号格式/限流）会连续失败 N 次，
        # 全部打印只会把关键信息刷没
        print(f"  首个失败样本：{first_fail}", file=sys.stderr)
        if throttled:
            print("  部分账号被网关登录限流拦下 —— 放宽限流后重跑：\n"
                  "    GW_LOGIN_RATE_REPLENISH=500 GW_LOGIN_RATE_BURST=1000 启动 judge-gateway",
                  file=sys.stderr)
        print("  账号池不完整 —— 压测前请先解决，否则线程会因拿不到 token 而全线 401。", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
