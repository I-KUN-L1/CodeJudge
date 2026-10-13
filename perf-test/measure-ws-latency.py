#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
判题进度 WebSocket 推送延迟实测脚本。

测什么：judge-submission 判题状态变化 → judge-common WsPush 基座（Redis 广播通道
judge:ws:broadcast）→ 网关 → 前端收帧 的端到端推送延迟。

怎么测：推送帧（WsEnvelope）自带 ts 字段 —— 帧构造时打点（System.currentTimeMillis()，
即服务端推送发起时刻，judge-common 首版即有，无需服务端改造）。客户端逐帧取
「本机收帧时刻 − 帧内 ts」即单帧推送延迟。测于同一台宿主机，收发两端同钟，
时钟同步误差可忽略（结论适用前提，报告已注明）。

口径：
  样本   = SUB_PROGRESS + SUB_RESULT 帧（判题进度推送本体，简历指标只算这两类）；
  参考帧 = CONNECTED / SNAPSHOT（握手即推，走同一条 WS 通道，单列不计入推送延迟）；
  丢帧   = 提交成功但直到超时仍未收到 SUB_RESULT（含连接前已终态）；
  连接失败 = WS 握手失败次数（重试一次后仍失败计 1）。

账号：perf-test/csv/users.csv（13950000000+N / 123456），串行组 1 人，并发组 10 人。
题目：4001（A+B），Java 提交确定 AC；nonce 注释规避提交幂等。
限流：提交限流 30 次/分钟/人 —— 串行组相邻两次提交间隔强制 ≥ 2.2s（≤ 27 次/分钟）。

用法：
  py -3 perf-test/measure-ws-latency.py                        # 串行 50 + 并发 5 轮 × 10 人
  py -3 perf-test/measure-ws-latency.py --serial 2 --rounds 1  # 冒烟

输出：控制台统计 + logs/ws-latency-<时间戳>.json
"""

import argparse
import csv
import json
import math
import pathlib
import statistics
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import requests
from websockets.sync.client import connect as ws_connect

ROOT = pathlib.Path(__file__).resolve().parent.parent
GATEWAY = "http://localhost:9080"
WS_BASE = "ws://localhost:9080"
PROBLEM_ID = 4001
LANGUAGE = "JAVA"
RUN_TS = time.strftime("%Y%m%d-%H%M%S")

# 4001（A+B）的 Java AC 模板；类名必须 Main（沙箱按 /src/Main.java 编译）
JAVA_TMPL = (
    "import java.util.Scanner;\n"
    "public class Main {\n"
    "    public static void main(String[] args) {\n"
    "        Scanner sc = new Scanner(System.in);\n"
    "        long a = sc.nextLong(), b = sc.nextLong();\n"
    "        System.out.println(a + b);\n"
    "    }\n"
    "}\n"
)

# 串行组相邻两次 POST 的最小间隔：30 次/分钟限流下留出安全余量（≤27 次/分钟）
SERIAL_SUBMIT_GAP_S = 2.2

PUSH_TYPES = ("SUB_PROGRESS", "SUB_RESULT")
REF_TYPES = ("CONNECTED", "SNAPSHOT")


def read_users(path, n):
    with open(path, newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    if len(rows) < n:
        raise SystemExit(f"账号池不足：需要 {n}，{path} 只有 {len(rows)}")
    return [(r["cellPhone"].strip(), r["password"].strip()) for r in rows[:n]]


def login(phone, password, tries=12):
    """登录拿 JWT；网关登录限流 2/s + burst 5，429 时退避重试。"""
    last = ""
    for i in range(tries):
        try:
            r = requests.post(f"{GATEWAY}/accounts/login",
                              json={"cellPhone": phone, "password": password}, timeout=15)
            d = r.json()
            token = (d.get("data") or {}).get("accessToken")
            if token:
                return token
            last = f"{r.status_code} {d.get('msg', '')}"
        except Exception as e:  # noqa: BLE001
            last = f"{type(e).__name__}: {e}"
        time.sleep(min(0.5 * (i + 1), 2.5))
    raise SystemExit(f"登录失败 phone={phone}：{last}")


def submit_once(token, idx):
    """提交一条确定 AC 的 Java 提交，返回 (sid, err)。nonce 注释规避幂等。"""
    code = JAVA_TMPL + f"// ws-latency-{RUN_TS}-{idx}\n"
    try:
        r = requests.post(f"{GATEWAY}/submissions",
                          headers={"Authorization": f"Bearer {token}"},
                          json={"problemId": PROBLEM_ID, "contestId": 0,
                                "language": LANGUAGE, "code": code},
                          timeout=20)
        d = r.json()
        data = d.get("data") or {}
        if d.get("code") == 200 and data.get("id"):
            return str(data["id"]), None
        return None, f"HTTP {d.get('code')} {d.get('msg')}"
    except Exception as e:  # noqa: BLE001
        return None, f"{type(e).__name__}: {e}"


def watch(sid, token, deadline_s):
    """订阅 /ws/submissions/{sid} 并逐帧记录延迟，直到 SUB_RESULT 或超时。"""
    rec = {"sid": sid, "wsOk": False, "missedResult": False, "wsErr": None,
           "verdict": None, "frames": [], "refFrames": []}
    url = f"{WS_BASE}/ws/submissions/{sid}?token={token}"
    try:
        conn = ws_connect(url, open_timeout=10, close_timeout=5)
    except Exception as e:  # noqa: BLE001
        rec["wsErr"] = f"{type(e).__name__}: {e}"
        return rec
    rec["wsOk"] = True
    deadline = time.time() + deadline_s
    try:
        while time.time() < deadline:
            remaining = max(deadline - time.time(), 0.05)
            try:
                raw = conn.recv(timeout=remaining)
            except TimeoutError:  # websockets≥14 的 sync recv 超时抛内置 TimeoutError
                break
            recv_ms = time.time() * 1000  # 尽量贴近 recv 返回时刻（统一换算成毫秒再减帧内 ts）
            try:
                msg = json.loads(raw)
            except Exception:  # noqa: BLE001
                continue
            mtype = msg.get("type", "")
            ts = msg.get("ts")
            if mtype in PUSH_TYPES and isinstance(ts, (int, float)):
                frame = {"type": mtype, "seq": msg.get("seq"), "ts": int(ts),
                         "latMs": round(recv_ms - ts, 1)}
                if mtype == "SUB_RESULT":
                    data = msg.get("data") or {}
                    rec["verdict"] = data.get("verdict")
                rec["frames"].append(frame)
                if mtype == "SUB_RESULT":
                    break
            elif mtype in REF_TYPES and isinstance(ts, (int, float)):
                rec["refFrames"].append({"type": mtype, "ts": int(ts),
                                         "latMs": round(recv_ms - ts, 1)})
                if mtype == "SNAPSHOT" and (msg.get("data") or {}).get("terminal"):
                    rec["missedResult"] = True  # 连接前已终态，本轮拿不到 SUB_RESULT
            elif mtype == "ERROR":
                rec["wsErr"] = "ERROR 帧：" + str((msg.get("data") or {}).get("message"))
                break
    except Exception as e:  # noqa: BLE001
        rec["wsErr"] = f"{type(e).__name__}: {e}"
    finally:
        try:
            conn.close()
        except Exception:  # noqa: BLE001
            pass
    if rec["verdict"] is None:
        rec["missedResult"] = True
    return rec


def run_one(token, idx, deadline_s):
    """完整一轮：提交 → 订阅 → 收帧。返回迭代记录。"""
    t_post = time.time()
    sid, err = submit_once(token, idx)
    it = {"idx": idx, "tPost": round(t_post, 3), "submitErr": err,
          "tAccept": round(time.time(), 3), "watch": None}
    if sid:
        it["watch"] = watch(sid, token, deadline_s)
    return it


def run_serial(token, n, deadline_s):
    print(f"\n[A组] 1 用户串行 × {n} 次提交（相邻提交间隔 ≥{SERIAL_SUBMIT_GAP_S}s）……")
    its = []
    last_post = 0.0
    for i in range(n):
        wait = SERIAL_SUBMIT_GAP_S - (time.time() - last_post)
        if wait > 0:
            time.sleep(wait)
        it = run_one(token, i, deadline_s)
        last_post = it["tPost"]
        w = it.get("watch") or {}
        if it["submitErr"] or w.get("missedResult") or w.get("wsErr"):
            print(f"  [{i + 1}/{n}] sid={w.get('sid')} ⚠ err={it['submitErr'] or w.get('wsErr') or '未收到终态'}")
        else:
            lat = [f["latMs"] for f in w["frames"]]
            print(f"  [{i + 1}/{n}] sid={w['sid']} verdict={w['verdict']} "
                  f"帧数={len(lat)} 延迟={lat}")
        its.append(it)
    return its


def run_conc(tokens, rounds, deadline_s):
    size = len(tokens)
    print(f"\n[B组] {size} 账号同时提交 × {rounds} 轮（组内各 1 条，共 {size * rounds} 条）……")
    its = []
    idx_base = 10_000
    for rd in range(rounds):
        print(f"  —— 第 {rd + 1}/{rounds} 轮 ——")
        with ThreadPoolExecutor(max_workers=size) as ex:
            batch = list(ex.map(
                lambda kv: run_one(tokens[kv[0]], idx_base + rd * size + kv[0], deadline_s),
                enumerate(tokens)))
        for k, it in enumerate(batch):
            w = it.get("watch") or {}
            if it["submitErr"] or w.get("missedResult") or w.get("wsErr"):
                print(f"    user#{k} sid={w.get('sid')} ⚠ err={it['submitErr'] or w.get('wsErr') or '未收到终态'}")
            else:
                lat = [f["latMs"] for f in w["frames"]]
                print(f"    user#{k} sid={w['sid']} verdict={w['verdict']} 帧数={len(lat)} 延迟={lat}")
        its.extend(batch)
        if rd < rounds - 1:
            time.sleep(2)
    return its


def pct(sorted_vals, q):
    """最近秩分位数（与简历口径一致：P50/P95/P99）。"""
    if not sorted_vals:
        return None
    idx = max(0, math.ceil(q * len(sorted_vals)) - 1)
    return sorted_vals[min(idx, len(sorted_vals) - 1)]


def summarize(name, iterations):
    push, ref = [], []
    for it in iterations:
        w = it.get("watch")
        if not w:
            continue
        push.extend([f["latMs"] for f in w["frames"]])
        ref.extend([f["latMs"] for f in w["refFrames"]])
    subm_ok = sum(1 for it in iterations if not it["submitErr"])
    ws_fail = sum(1 for it in iterations if it.get("watch") and it["watch"]["wsOk"] is False)
    missed = sum(1 for it in iterations if it.get("watch") and it["watch"]["missedResult"])
    non_ac = sum(1 for it in iterations
                 if it.get("watch") and it["watch"]["verdict"] not in (None, "AC")
                 and not it["watch"]["missedResult"])
    push_sorted = sorted(push)
    ref_sorted = sorted(ref)
    s = {
        "group": name, "iterations": len(iterations),
        "submitOk": subm_ok, "submitFailed": len(iterations) - subm_ok,
        "wsConnectFailed": ws_fail,
        "missedResult": missed,
        "nonAcVerdict": non_ac,
        "pushSamples": len(push),
        "pushMeanMs": round(statistics.fmean(push), 1) if push else None,
        "pushP50Ms": pct(push_sorted, 0.50), "pushP95Ms": pct(push_sorted, 0.95),
        "pushP99Ms": pct(push_sorted, 0.99), "pushMaxMs": push_sorted[-1] if push_sorted else None,
        "refSamples": len(ref),
        "refMeanMs": round(statistics.fmean(ref), 1) if ref else None,
        "refP95Ms": pct(ref_sorted, 0.95),
    }
    print(f"\n[{name}] 迭代={s['iterations']} 提交成功={s['submitOk']} 失败={s['submitFailed']} "
          f"WS连接失败={s['wsConnectFailed']} 未收到终态={s['missedResult']} 非AC={s['nonAcVerdict']}")
    if push:
        print(f"  推送样本 n={s['pushSamples']}  平均={s['pushMeanMs']}ms  "
              f"P50={s['pushP50Ms']}ms  P95={s['pushP95Ms']}ms  P99={s['pushP99Ms']}ms  "
              f"最大={s['pushMaxMs']}ms")
    else:
        print("  ⚠ 无推送帧样本")
    if ref:
        print(f"  （参考帧 n={s['refSamples']}  平均={s['refMeanMs']}ms  P95={s['refP95Ms']}ms）")
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", type=int, default=50, help="串行组提交次数（默认 50）")
    ap.add_argument("--rounds", type=int, default=5, help="并发组轮数（默认 5）")
    ap.add_argument("--conc", type=int, default=10, help="并发组账号数（默认 10）")
    ap.add_argument("--deadline", type=float, default=90,
                    help="单条提交等待终态的上限秒数（默认 90）")
    args = ap.parse_args()

    users = read_users(ROOT / "perf-test" / "csv" / "users.csv", max(args.conc, 1))
    print("=" * 78)
    print(f"WS 推送延迟实测 —— 题目 {PROBLEM_ID}（Java）网关 {GATEWAY}")
    print(f"串行 {args.serial} 次 + 并发 {args.conc} 人 × {args.rounds} 轮；"
          f"单条等待上限 {args.deadline:.0f}s")
    print("=" * 78)

    tokens = {}
    serial_phone = users[0][0]
    print(f"登录串行用户 {serial_phone} ……")
    tokens[serial_phone] = login(*users[0])
    print(f"登录 {args.conc} 个并发用户（限流 2/s，间隔 0.6s）……")
    for phone, pwd in users[:args.conc]:
        if phone not in tokens:
            tokens[phone] = login(phone, pwd)
            time.sleep(0.6)

    mem_free_gb = None
    try:
        import ctypes

        class MEMORYSTATUSEX(ctypes.Structure):
            _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong),
                        ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                        ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                        ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                        ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]

        st = MEMORYSTATUSEX()
        st.dwLength = ctypes.sizeof(MEMORYSTATUSEX)
        ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(st))
        mem_free_gb = round(st.ullAvailPhys / 1024 ** 3, 2)
    except Exception:  # noqa: BLE001
        pass
    print(f"宿主起测可用内存 {mem_free_gb} GB")

    serial_its = run_serial(tokens[serial_phone], args.serial, args.deadline)
    conc_its = run_conc([tokens[p] for p, _ in users[:args.conc]], args.rounds, args.deadline)

    s_serial = summarize("serial-1user", serial_its)
    s_conc = summarize("conc-10users", conc_its)

    all_push = sorted([f["latMs"] for it in serial_its + conc_its
                       if it.get("watch") for f in it["watch"]["frames"]])
    overall = {
        "samples": len(all_push),
        "meanMs": round(statistics.fmean(all_push), 1) if all_push else None,
        "p50Ms": pct(all_push, 0.50), "p95Ms": pct(all_push, 0.95), "p99Ms": pct(all_push, 0.99),
    }
    if all_push:
        print("\n" + "=" * 78)
        print(f"两组合计：n={overall['samples']}  平均={overall['meanMs']}ms  "
              f"P50={overall['p50Ms']}ms  P95={overall['p95Ms']}ms  P99={overall['p99Ms']}ms")
        print(f"简历回填：WS 推送延迟 P95 {overall['p95Ms']}ms"
              f"（{overall['samples']} 次推送样本，1 用户/10 并发两组）")
        print("=" * 78)

    out = ROOT / "logs" / f"ws-latency-{RUN_TS}.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps({
        "meta": {
            "ts": RUN_TS,
            "target": "judge-submission 判题进度 WS 推送（/ws/submissions/{id}）经网关 :9080",
            "pushPath": "MQ(tag=PROGRESS/RESULT) → SubmissionProgressPushService → "
                        "WsEnvelope(ts=构造时刻) → Redis broadcast(judge:ws:broadcast) → "
                        "本实例投递 → 网关 → 客户端",
            "latencyDef": "客户端收帧时刻 − 帧内 ts（服务端推送发起时刻，epochMs）；"
                          "同宿主机同钟，时钟同步误差可忽略",
            "gateway": GATEWAY, "problemId": PROBLEM_ID, "language": LANGUAGE,
            "accounts": "perf-test/csv/users.csv（13950000000+N / 123456）",
            "throttle": "提交限流 30/min/人（串行组间隔 ≥2.2s）；"
                        "推送侧 CJ_JUDGE_PROGRESS_MIN_INTERVAL_MS=800（中间态合并，不影响终态帧）",
            "hostFreeMemGbAtStart": mem_free_gb,
        },
        "summary": {"serial": s_serial, "conc": s_conc, "overall": overall},
        "iterations": {"serial": serial_its, "conc": conc_its},
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n明细已写入 {out.relative_to(ROOT)}")


if __name__ == "__main__":
    sys.exit(main())
