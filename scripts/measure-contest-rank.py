#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
竞赛榜单压测数据构造 + 「提交 → 榜单可见」更新链路测量。

两段职责：
  A. 构造有真实规模的竞赛：60 名选手（压测账号池 13950000000..59）、2 道 AC 题、
     ACM 赛制、6 小时窗口、封榜 30 分钟 —— 供 JMeter 榜单查询压测与封榜行为验证。
  B. 更新链路 P99：每名选手串行提交第 2 题（AC），逐条记录
       t_submit  = 提交受理时刻（HTTP 200）
       t_final   = 判题终态时刻（轮询 /submissions/{id}，200ms 粒度）
       t_rank    = Redis 实时榜 ZSet 权重段变化的时刻（轮询 ZSCORE，200ms 粒度）
     输出两段口径：受理→榜单可见（含判题） 与 终态→榜单可见（榜单更新本身）。

硬约束：只写真实采集数据；任何一步失败如实报错退出，不估算、不编造。

用法：
    python scripts/measure-contest-rank.py --players 60
输出：
    控制台表格 + logs/rank-update-<时间戳>.json
"""

import argparse
import json
import pathlib
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import redis
import requests

ROOT = pathlib.Path(__file__).resolve().parent.parent
RUN_TS = time.strftime("%Y%m%d-%H%M%S")
GATEWAY = "http://127.0.0.1:9080"
TIMEOUT = 20

PROBLEM_1 = 4001   # A + B
PROBLEM_2 = 4002   # 1 到 n 求和
AC_1 = "a, b = map(int, input().split()); print(a + b)"
AC_2 = "n = int(input()); print(n * (n + 1) // 2)"
WEIGHT_FACTOR = 1_000_000_000_000   # 与 ContestRankService.WEIGHT_FACTOR 一致
POLL = 0.2                          # 轮询粒度（秒）


def env_value(key: str) -> str:
    for line in (ROOT / ".env").read_text(encoding="utf-8", errors="replace").splitlines():
        s = line.strip()
        if s.startswith("#") or "=" not in s:
            continue
        k, v = s.split("=", 1)
        if k.strip() == key:
            return v.strip()
    raise SystemExit(f".env 缺少 {key}")


def login(phone: str, password: str, retries: int = 6) -> str:
    """登录（网关令牌桶 429 时退避重试）。"""
    last = None
    for _ in range(retries):
        r = requests.post(f"{GATEWAY}/accounts/login",
                          json={"cellPhone": phone, "password": password}, timeout=TIMEOUT)
        try:
            d = r.json()
        except ValueError:          # 429 等响应体为空，按限流处理退避重试
            d = {"code": r.status_code}
        last = d.get("code")
        tok = (d.get("data") or {}).get("accessToken")
        if d.get("code") == 200 and tok:
            return tok
        time.sleep(1.2)
    raise SystemExit(f"登录失败 phone={phone} code={last}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--players", type=int, default=60, help="选手数（默认 60）")
    ap.add_argument("--submit-threads", type=int, default=1, help="第 2 题提交并发（默认 1 串行）")
    args = ap.parse_args()

    admin_pass = env_value("CJ_ADMIN_INIT_PASSWORD")
    admin_tok = login("13800000000", admin_pass)
    admin_h = {"Authorization": f"Bearer {admin_tok}"}

    r = redis.Redis(host="127.0.0.1", port=int(env_value("REDIS_BIND_PORT")),
                    password=env_value("REDIS_PASSWORD"), db=0, decode_responses=True)
    r.ping()

    # ---------- A1. 创建竞赛 ----------
    now = time.time()
    fmt = lambda ts: time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(ts))
    form = {
        "title": f"【PERF】榜单压测赛 {RUN_TS}",
        "description": "性能标定专用：60 选手 / 2 题 / ACM",
        "rule": "ACM",
        "startTime": fmt(now - 300),          # 已开始 5 分钟
        "endTime": fmt(now + 6 * 3600),       # 6 小时后结束，测试窗口内不会自动结束
        "freezeMinutes": 30,
        "penaltyMinutes": 20,
        "problems": [
            {"problemId": PROBLEM_1, "label": "A", "displayOrder": 1, "fullScore": 100},
            {"problemId": PROBLEM_2, "label": "B", "displayOrder": 2, "fullScore": 100},
        ],
    }
    d = requests.post(f"{GATEWAY}/contests", headers=admin_h, json=form, timeout=TIMEOUT).json()
    if d.get("code") != 200:
        raise SystemExit(f"创建竞赛失败：{d}")
    cid = d["data"]["id"]
    print(f"[A1] 竞赛已创建 contestId={cid}（60 分钟后不封榜：freezeAt={fmt(now + 6*3600 - 1800)}，窗口内不触发）")

    # ---------- A2. 报名 ----------
    players = []
    for i in range(args.players):
        phone = f"139{50000000 + i:08d}"
        tok = login(phone, "123456")
        h = {"Authorization": f"Bearer {tok}"}
        d = requests.post(f"{GATEWAY}/contests/{cid}/register", headers=h, timeout=TIMEOUT).json()
        if d.get("code") != 200:
            raise SystemExit(f"报名失败 phone={phone}：{d}")
        uid = (requests.get(f"{GATEWAY}/users/me", headers=h, timeout=TIMEOUT).json().get("data") or {}).get("id")
        players.append({"phone": phone, "uid": uid, "h": h})
    print(f"[A2] 报名完成：{len(players)} 名选手")

    # ---------- A3. 第 1 题 AC（8 并发），建立初始榜单 ----------
    def submit_p1(p):
        d = requests.post(f"{GATEWAY}/submissions", headers=p["h"], timeout=TIMEOUT,
                          json={"problemId": PROBLEM_1, "contestId": cid,
                                "language": "PYTHON", "code": AC_1 + f"\n# perf-{RUN_TS}-{p['phone']}-p1"}).json()
        if d.get("code") != 200:
            raise SystemExit(f"第 1 题提交失败 {p['phone']}：{d}")
        return p, d["data"]["id"], time.time()

    t0 = time.time()
    with ThreadPoolExecutor(max_workers=8) as ex:
        round1 = list(ex.map(submit_p1, players))
    print(f"[A3] 第 1 题已提交 {len(round1)} 条（{time.time() - t0:.1f}s），等待全部终态……")
    wait_terminal(round1)
    live_key = f"judge:contest:rank:{cid}"
    n1 = r.zcard(live_key)
    print(f"[A3] 实时榜 ZSet 成员数 = {n1}（期望 {args.players}）")
    if n1 != args.players:
        raise SystemExit(f"榜单成员数异常：{n1} != {args.players}")

    # ---------- B. 第 2 题串行提交：测「受理→终态→榜单可见」 ----------
    print(f"\n[B] 第 2 题更新链路：{args.players} 条，串行={args.submit_threads == 1}，轮询粒度 {POLL}s")
    samples = []

    def watch(sid, uid, t_submit, h):
        """轮询终态与榜单权重段（weight 1 -> 2）。"""
        t_final = t_rank = None
        deadline = time.time() + 180
        while time.time() < deadline:
            if t_final is None:
                d = requests.get(f"{GATEWAY}/submissions/{sid}", headers=h, timeout=TIMEOUT).json()
                st = (d.get("data") or {}).get("status")
                if st not in ("PENDING", "JUDGING", None):
                    t_final = time.time()
            sc = r.zscore(f"judge:contest:rank:{cid}", str(uid))
            if t_rank is None and sc is not None and int(sc) // WEIGHT_FACTOR >= 2:
                t_rank = time.time()
            if t_final and t_rank:
                break
            time.sleep(POLL)
        return t_final, t_rank

    def run_one(idx):
        p = players[idx]
        d = requests.post(f"{GATEWAY}/submissions", headers=p["h"], timeout=TIMEOUT,
                          json={"problemId": PROBLEM_2, "contestId": cid,
                                "language": "PYTHON", "code": AC_2 + f"\n# perf-{RUN_TS}-{p['phone']}-p2"}).json()
        if d.get("code") != 200:
            raise SystemExit(f"第 2 题提交失败 {p['phone']}：{d}")
        t_submit = time.time()
        t_final, t_rank = watch(d["data"]["id"], p["uid"], t_submit, p["h"])
        if t_rank is None:
            raise SystemExit(f"榜单 180s 内未更新：phone={p['phone']}")
        return {"phone": p["phone"], "accept_to_rank": t_rank - t_submit,
                "final_to_rank": (t_rank - t_final) if t_final else None,
                "accept_to_final": (t_final - t_submit) if t_final else None}

    t0 = time.time()
    if args.submit_threads == 1:
        for i in range(args.players):
            samples.append(run_one(i))
            if (i + 1) % 15 == 0:
                print(f"      {i + 1}/{args.players} 完成（累计 {time.time() - t0:.0f}s）")
            time.sleep(0.3)
    else:
        with ThreadPoolExecutor(max_workers=args.submit_threads) as ex:
            samples = list(ex.map(run_one, range(args.players)))

    # ---------- 统计 ----------
    def stats(vals):
        vals = sorted(v for v in vals if v is not None)
        n = len(vals)
        return {"n": n, "avg": round(sum(vals) / n, 3),
                "p50": round(vals[min(int(n * .5), n - 1)], 3),
                "p95": round(vals[min(int(n * .95), n - 1)], 3),
                "p99": round(vals[min(int(n * .99), n - 1)], 3),
                "max": round(vals[-1], 3)}

    a2r = stats([s["accept_to_rank"] for s in samples])
    f2r = stats([s["final_to_rank"] for s in samples])
    a2f = stats([s["accept_to_final"] for s in samples])
    print(f"\n[B] 受理→榜单可见（含判题）：P50 {a2r['p50']}s  P95 {a2r['p95']}s  P99 {a2r['p99']}s  max {a2r['max']}s（n={a2r['n']}）")
    print(f"[B] 终态→榜单可见（榜单更新本身）：P50 {f2r['p50']}s  P95 {f2r['p95']}s  P99 {f2r['p99']}s  max {f2r['max']}s")
    print(f"[B] 参考 受理→终态：P50 {a2f['p50']}s  P95 {a2f['p95']}s")

    out = ROOT / "logs" / f"rank-update-{RUN_TS}.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps({
        "ts": RUN_TS, "contestId": cid, "players": args.players,
        "live_key": live_key,
        "accept_to_rank_s": a2r, "final_to_rank_s": f2r, "accept_to_final_s": a2f,
        "samples": samples,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n明细已写入 {out.relative_to(ROOT)}")


def wait_terminal(round1):
    """等待一批提交全部进入终态，返回 verdict 分布。"""
    pending = {sid: h for p, sid, _ in round1 for h in [p["h"]]}
    verdicts = {}
    deadline = time.time() + 300
    while pending and time.time() < deadline:
        for sid in list(pending):
            d = requests.get(f"{GATEWAY}/submissions/{sid}", headers=pending[sid], timeout=TIMEOUT).json()
            data = d.get("data") or {}
            if data.get("status") not in ("PENDING", "JUDGING", None):
                v = data.get("verdict")
                verdicts[v] = verdicts.get(v, 0) + 1
                pending.pop(sid)
        if pending:
            time.sleep(0.5)
    if pending:
        raise SystemExit(f"{len(pending)} 条提交 300s 未终态")
    print(f"      verdict 分布：{verdicts}")
    if verdicts.get("AC") != len(round1):
        raise SystemExit(f"存在非 AC 结果，数据构造失败：{verdicts}")


if __name__ == "__main__":
    main()
