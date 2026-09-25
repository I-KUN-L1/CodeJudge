#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
判题吞吐标定脚本 —— 替换 docs/PERF.md §3.3 里那个**已证伪**的「单实例 ≈1.5 题/秒」。

为什么要重标：
  PERF.md §3.3 的 1.5 题/秒 是**用积压排空速率倒推**的，而当时的排空速率被
  「判题首次投递缺失」缺陷 + `pending-rescue` 的 10s 扫描周期限流 ——
  量到的是**故障路径下的补偿节奏**，不是 worker 的处理能力。
  该缺陷已于 2026-09-22 修复（见 docs/P3-REPORT.md 顶部勘误）。
  本脚本改为**直接测**：投一批互不相同的提交，量「最后一条受理 → 全部终态」的排空窗口。

度量口径（三项分开，不要混）：
  accept_rate  = N / (最后一条受理时刻 − 第一条受理时刻)     —— 提交接口的受理能力
  drain_window = 全部终态时刻 − 最后一条受理时刻              —— 纯消费窗口
  throughput   = N / drain_window                            —— **判题吞吐（本题关注项）**
  e2e_latency  = 单条「受理 → 终态」                         —— 用户感知时延（受队列排队影响）

 前置：提交限流必须临时放宽（默认每人 30 次/分钟，见 CJ_SUBMIT_RATE_LIMIT）。
   放宽/恢复的完整流程见脚本尾部 `--help-limit` 输出；测完**必须恢复并验证**，
   否则线上任何人 10 秒内就能把队列灌满（硬规则第 6 条同源问题）。

用法：
    python scripts/measure-judge-throughput.py --n 150 --label 1-worker
    python scripts/measure-judge-throughput.py --n 150 --label 3-worker

输出：控制台表格 + logs/throughput-<label>-<时间戳>.json（供跨轮次对比）
"""

import argparse
import json
import os
import pathlib
import random
import statistics
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import requests

GATEWAY = os.environ.get("CJ_PERF_GATEWAY", "http://localhost:9080")
TIMEOUT = 20
STUDENT_PHONE = os.environ.get("CJ_PERF_PHONE", "13900000001")
STUDENT_PASS = os.environ.get("CJ_PERF_PASS", "123456")

# 被测题目：4001（A+B）—— 最快的判题路径，用于测 worker 的**吞吐上限**，
# 而不是被某个题目的重负载（大输入 / 编译耗时）带偏。
PROBLEM_ID = 4001
AC_PY = "a, b = map(int, input().split()); print(a + b)"

ROOT = pathlib.Path(__file__).resolve().parent.parent
RUN_TS = time.strftime("%Y%m%d-%H%M%S")


def _env_file_value(key: str) -> str:
    """从仓库根 .env 读取（管理员口令取自 CJ_ADMIN_INIT_PASSWORD，不硬编码）。"""
    try:
        for line in (ROOT / ".env").read_text(encoding="utf-8", errors="replace").splitlines():
            s = line.strip()
            if s.startswith("#") or "=" not in s:
                continue
            k, v = s.split("=", 1)
            if k.strip() == key:
                return v.strip()
    except OSError:
        pass
    return ""


def login(phone, password):
    r = requests.post(f"{GATEWAY}/accounts/login",
                      json={"cellPhone": phone, "password": password}, timeout=TIMEOUT)
    token = (r.json().get("data") or {}).get("accessToken")
    if not token:
        raise SystemExit(f"登录失败 phone={phone}：{r.text[:200]}")
    return {"Authorization": f"Bearer {token}"}


def workers_snapshot(admin_headers):
    """返回 (在线 worker 数, backlog, running 任务总数)。任一失败返回 None 分量。"""
    try:
        ws = requests.get(f"{GATEWAY}/workers", headers=admin_headers, timeout=TIMEOUT).json()
        wlist = ws.get("data") or []
        ms = requests.get(f"{GATEWAY}/workers/metrics", headers=admin_headers, timeout=TIMEOUT).json()
        m = ms.get("data") or {}
        return len(wlist), int(m.get("queueBacklog") or 0), sum(int(w.get("runningTasks") or 0) for w in wlist)
    except Exception:
        return None, None, None


def submit_one(headers, idx):
    """提交一条互不相同的 AC 提交（nonce 注释保证不命中幂等）。"""
    code = AC_PY + f"\n# perf-{RUN_TS}-{idx}"
    t0 = time.time()
    try:
        r = requests.post(f"{GATEWAY}/submissions", headers=headers, timeout=TIMEOUT,
                          json={"problemId": PROBLEM_ID, "contestId": 0,
                                "language": "PYTHON", "code": code})
        d = r.json()
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "err": f"{type(e).__name__}", "t0": t0, "t1": time.time()}
    data = d.get("data") or {}
    return {"ok": d.get("code") == 200 and bool(data.get("id")),
            "sid": data.get("id"), "status": data.get("status"),
            "err": None if d.get("code") == 200 else f"{d.get('code')} {d.get('msg')}",
            "t0": t0, "t1": time.time()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=150, help="提交条数（默认 150）")
    ap.add_argument("--label", default="unlabeled", help="本轮标签，用于输出文件名与打印")
    ap.add_argument("--threads", type=int, default=16, help="提交并发线程数")
    ap.add_argument("--timeout", type=float, default=300, help="排空等待上限（秒）")
    ap.add_argument("--sample", type=int, default=25, help="端到端时延采样条数")
    ap.add_argument("--help-limit", action="store_true", help="打印放宽/恢复提交限流的步骤后退出")
    args = ap.parse_args()

    if args.help_limit:
        print(LIMIT_HELP)
        return

    print("=" * 78)
    print(f"判题吞吐标定 —— label={args.label}  N={args.n}  并发={args.threads}")
    print("=" * 78)

    student = login(STUDENT_PHONE, STUDENT_PASS)
    admin_pass = os.environ.get("CJ_PERF_ADMIN_PASS") or _env_file_value("CJ_ADMIN_INIT_PASSWORD")
    admin = login(os.environ.get("CJ_PERF_ADMIN_PHONE", "13800000000"), admin_pass)

    nw, backlog0, running0 = workers_snapshot(admin)
    print(f"在线 worker={nw}  起始 backlog={backlog0}  running={running0}")
    if nw is None:
        raise SystemExit("无法读取 /workers —— 请确认管理员凭据与 judge-worker 在线")
    if backlog0:
        print(f"⚠ 起始 backlog 非 0（{backlog0}）—— 标定要求空队列起步，请先等队列排空")

    # ---------- 1. 灌入 ----------
    print(f"\n[1/4] 并发提交 {args.n} 条（{args.threads} 线程）……")
    t_start = time.time()
    with ThreadPoolExecutor(max_workers=args.threads) as ex:
        results = list(ex.map(lambda i: submit_one(student, i), range(args.n)))
    ok = [r for r in results if r["ok"]]
    bad = [r for r in results if not r["ok"]]
    if not ok:
        raise SystemExit(f"全部提交失败，首个错误：{bad[0].get('err') if bad else '未知'}")
    t_first, t_last = min(r["t0"] for r in ok), max(r["t1"] for r in ok)
    accept_rate = len(ok) / max(t_last - t_first, 1e-6)
    print(f"      成功 {len(ok)} / 失败 {len(bad)}   受理窗口 {t_last - t_first:.2f}s  "
          f"受理速率 {accept_rate:.1f} 提交/s")
    if bad:
        errs = {}
        for b in bad:
            errs[b.get("err")] = errs.get(b.get("err"), 0) + 1
        print(f"      ⚠ 失败分布：{errs}")
        print("      ⚠ 若含「提交过于频繁」→ 限流未放宽，本轮吞吐数据不可用（见 --help-limit）")

    sids = [(r["sid"], r["t1"]) for r in ok]

    # ---------- 2. 排空 + 与排空同步进行的端到端时延采样 ----------
    # 采样必须**在排空过程中**做。若等队列排空后再逐条查状态，查到的"终态时刻"全部等于
    #    t_drain，于是每条时延都被算成"整批等待时间" —— 实测踩过：P50 151.04s / P95 152.02s
    #    全部挤在末尾，数字整齐得像真的，其实是采样偏差，不是真实分布。
    sample = dict(random.sample(sids, min(args.sample, len(sids))))
    pending = dict(sample)
    lat, series = [], []
    print(f"\n[2/4] 等待队列排空（上限 {args.timeout:.0f}s），同步采样 {len(sample)} 条时延……")
    peak, t_drain, idle = backlog0, None, 0
    while time.time() - t_last < args.timeout:
        _, bl, run = workers_snapshot(admin)
        if bl is None:
            drained = False
        else:
            peak = max(peak, bl)
            series.append((round(time.time() - t_last, 1), bl, run))
            drained = (bl == 0 and run == 0)
        # 采样查询：条数少（默认 12），每轮全查，开销相对上千条判题可忽略
        for sid in list(pending):
            try:
                d = requests.get(f"{GATEWAY}/submissions/{sid}", headers=student, timeout=TIMEOUT).json()
                if (d.get("data") or {}).get("status") not in ("PENDING", "JUDGING"):
                    lat.append(time.time() - pending.pop(sid))
            except Exception:  # noqa: BLE001   单次失败不影响排空判定，下轮重试
                pass
        if drained:
            idle += 1
            if idle >= 3:          # 连续 3 次（≈1.2s）为空才判排空，避免采样抖动误判
                t_drain = time.time()
                break
        else:
            idle = 0
        time.sleep(0.4)
    if t_drain is None:
        print("      ⚠ 超时未排空 —— 吞吐按下限报告")
    drain_window = (t_drain or time.time()) - t_last
    throughput = len(ok) / max(drain_window, 1e-6)
    print(f"      积压峰值 {peak}   排空窗口 {drain_window:.2f}s   "
          f"→ 判题吞吐 {throughput:.2f} 题/s")

    # ---------- 3. 时延统计 ----------
    lat.sort()
    p50 = statistics.median(lat) if lat else float("nan")
    p95 = lat[min(int(len(lat) * 0.95), len(lat) - 1)] if lat else float("nan")
    censored = len(sample) - len(lat)
    print(f"\n[3/4] 端到端时延（受理→终态）：样本 {len(lat)}/{len(sample)}  "
          f"P50 {p50:.2f}s  P95 {p95:.2f}s"
          + (f"（{censored} 条到判空时仍未结算，已截尾）" if censored else ""))

    # ---------- 3.5 正确性核对（吞吐数字必须伴随正确性，否则"快"可能只是"丢结果快"） ----------
    # 关掉主路径/丢消息这类缺陷的典型表现就是"吞吐好看但结果不对"，故必须复核终态 verdict。
    verdicts = {}
    for sid in sample:
        try:
            d = requests.get(f"{GATEWAY}/submissions/{sid}", headers=student, timeout=TIMEOUT).json()
            v = (d.get("data") or {}).get("verdict")
            verdicts[v] = verdicts.get(v, 0) + 1
        except Exception:  # noqa: BLE001
            verdicts["QUERY_FAILED"] = verdicts.get("QUERY_FAILED", 0) + 1
    correct = verdicts.get("AC", 0) == len(sample)
    print(f"\n[3.5/4] 正确性核对：抽样 {len(sample)} 条 verdict 分布 {verdicts}  "
          f"→ {'全部 AC ✔' if correct else '⚠ 存在非 AC，吞吐数字不可信'}")

    # ---------- 4. 落盘 ----------
    summary = {
        "label": args.label, "ts": RUN_TS, "n": args.n, "threads": args.threads,
        "workers_online": nw, "backlog_start": backlog0, "backlog_peak": peak,
        "accepted": len(ok), "failed": len(bad),
        "accept_window_s": round(t_last - t_first, 3), "accept_rate": round(accept_rate, 2),
        "drain_window_s": round(drain_window, 3), "throughput_tps": round(throughput, 2),
        "per_worker_tps": round(throughput / nw, 2) if nw else None,
        "e2e_p50_s": round(p50, 3) if lat else None, "e2e_p95_s": round(p95, 3) if lat else None,
        "e2e_sample": len(lat), "e2e_censored": censored,
        "verdicts": verdicts, "all_ac": correct,
        "failures": {str(b.get("err")): 1 for b in bad} if bad else {},
    }
    out = ROOT / "logs" / f"throughput-{args.label}-{RUN_TS}.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

    print("\n" + "=" * 78)
    print(f"worker={nw}  吞吐 {throughput:.2f} 题/s（单 worker {throughput / nw:.2f} 题/s）"
          f"  P50 {p50:.2f}s  P95 {p95:.2f}s")
    print(f"明细已写入 {out.relative_to(ROOT)}")
    print("=" * 78)
    print(LIMIT_HELP)


LIMIT_HELP = """
────────────────────────────────────────────────────────────────────────────
提交限流必须临时放宽（否则第 31 条起返回 400「提交过于频繁」，本轮数据作废）
  ① 改 .env： CJ_SUBMIT_RATE_LIMIT=30  →  10000
  ② 重启后端（先结束 --wait 看护父进程会连带回收全部子服务，见 CONTEXT §5.5）
     python scripts/dev-start-backend.py --wait
  ③ 跑本脚本
  ④ **恢复** .env 的 CJ_SUBMIT_RATE_LIMIT=30，再重启一次
  ⑤ **验证恢复**：连续 31 次提交，第 31 次必须返回 400「提交过于频繁」
     —— 不验证的"恢复"等于没恢复（这正是硬规则第 6 条要防的）
────────────────────────────────────────────────────────────────────────────
"""


if __name__ == "__main__":
    main()
