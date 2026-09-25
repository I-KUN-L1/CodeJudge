#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CodeJudge 成果指标采集器 —— 补 verify-p*.py 不产出的**耗时分布**。

为什么单独一个脚本：
  verify-p1..p6 只做**布尔断言**（通过 / 不通过），刻意不产出量化分布。
  而 README 的「成果与指标」表与 docs/PERF.md 需要 P50/P95/P99 级别的数字，
  二者目标不同，硬塞进验收脚本会让「验收」与「测量」互相污染（验收要稳，
  测量要可调参）。故独立成篇。

采集口径（**三套口径不能混，混了就是误导**）：
  · judge_e2e_ms   —— 提交受理返回 → 轮询到终态。含容器启停 + 编译 + 全部用例。
                      这是「用户感知的判题等待时间」。
  · sandbox_ms     —— 接口返回的 `timeMs`。**只含沙箱内程序执行本身**，
                      不含容器启停与编译。面试被追问时这两者必须能分开讲。
  · push_latency_ms—— WS 收到终态帧的本地时刻 − 信封里的服务端 `ts`。
                      同机、单向、含广播调度；跨进程但**同一 OS 时钟源**。

用法（需 8 服务 + 4 沙箱镜像就绪）：
    # 必须用带 websocket-client 的解释器（见 docs/CONTEXT.md §7）
    C:/Users/20670/.workbuddy/binaries/python/envs/default/Scripts/python.exe \
        scripts/collect-metrics.py
    ... scripts/collect-metrics.py --section judge          # 只测判题耗时
    ... scripts/collect-metrics.py --section ws --n 20      # 只测 WS 推送延迟
    ... scripts/collect-metrics.py --section rank
    ... scripts/collect-metrics.py --section ai             # AI 首字节（TTFT）
    ... scripts/collect-metrics.py --section workers        # 只读快照，不压测

环境变量：
    CJ_METRICS_GATEWAY  默认 http://localhost:9080
    CJ_METRICS_PHONE    默认 13900000001
    CJ_METRICS_PASS     默认 123456
    CJ_METRICS_PROBLEM  默认 4001（A+B，4 语言都有正解）
    CJ_METRICS_N        默认 10（每语言样本数 / WS 样本数）
    CJ_METRICS_ADMIN_PHONE / CJ_METRICS_ADMIN_PASS（可选，用于 workers 段）
    CJ_METRICS_OUT      默认 perf-test/results/metrics-<时间戳>.json

退出码：0 = 全部段落采到样本；1 = 有段落零样本（**假绿比 FAIL 危险**，见 CONTEXT §3 第 16 条）。
"""

import argparse
import json
import os
import pathlib
import re
import statistics
import sys
import time

import requests
import websocket

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
RESULT_DIR = ROOT / "perf-test" / "results"

GATEWAY = os.environ.get("CJ_METRICS_GATEWAY", "http://localhost:9080").rstrip("/")
WS_BASE = GATEWAY.replace("https://", "wss://").replace("http://", "ws://")
PHONE = os.environ.get("CJ_METRICS_PHONE", "13900000001")
PASSWORD = os.environ.get("CJ_METRICS_PASS", "123456")
PROBLEM = int(os.environ.get("CJ_METRICS_PROBLEM", "4001"))
N = int(os.environ.get("CJ_METRICS_N", "10"))
WARMUP = 2                     # 每语言先跑的预热次数（不计入样本）
TIMEOUT = 20
POLL_INTERVAL = 0.2            # 轮询终态的间隔；e2e 的量化精度即受此限制
TERMINAL = {"AC", "WA", "TLE", "MLE", "RE", "CE", "SE"}

# ---------------------------------------------------------------
# 4 语言的 A+B 正解。
# 每次提交都要注入 nonce 注释 —— **提交幂等**（60s 内同码重复提交会复用既有
#    submissionId，见 verify-p3 B2）会让第二次开始的样本耗时≈0，把分布彻底污染。
#
# 用 `__NONCE__`.replace() 而**不是** str.format() —— C++/Go 源码里到处都是
#    裸花括号（`int main(){` / `func main() {`），format 会把它们当占位符直接抛
#    KeyError。（初版就踩了这个坑，py_compile 查不出来，只有渲染模板才暴露。）
# ---------------------------------------------------------------
NONCE = "__NONCE__"

CODE = {
    "PYTHON": (
        "import sys\n"
        "a, b = map(int, sys.stdin.read().split())\n"
        "print(a + b)\n"
        f"# nonce {NONCE}\n"
    ),
    "JAVA": (
        "import java.util.*;\n"
        "public class Main {\n"
        "  public static void main(String[] args) {\n"
        "    Scanner sc = new Scanner(System.in);\n"
        "    long a = sc.nextLong(), b = sc.nextLong();\n"
        "    System.out.println(a + b);\n"
        "  }\n"
        "}\n"
        f"// nonce {NONCE}\n"
    ),
    "CPP": (
        "#include <iostream>\n"
        "using namespace std;\n"
        "int main(){ long long a,b; cin>>a>>b; cout<<a+b<<endl; return 0; }\n"
        f"// nonce {NONCE}\n"
    ),
    "GO": (
        "package main\n"
        "import \"fmt\"\n"
        "func main() {\n"
        "  var a, b int64\n"
        "  fmt.Scan(&a, &b)\n"
        "  fmt.Println(a + b)\n"
        "}\n"
        f"// nonce {NONCE}\n"
    ),
}


def render_nonce(tpl, n):
    """注入 nonce，绕开提交幂等（不可用 str.format，见 CODE 上方注释）。"""
    return tpl.replace(NONCE, str(n))



# ============================ 统计与输出 ============================

def percentile(sorted_vals, p):
    """线性插值分位数 —— 与 run-perf.py / JMeter HTML 报告同一算法，两边数字能对上。"""
    if not sorted_vals:
        return None
    k = (len(sorted_vals) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(sorted_vals) - 1)
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (k - lo)


def describe(vals, unit="ms"):
    """样本分布描述。**零样本返回 None 而非 0** —— 0 会被误读成「很快」。"""
    if not vals:
        return None
    s = sorted(vals)
    return {
        "unit": unit,
        "n": len(s),
        "min": round(s[0], 1),
        "p50": round(percentile(s, 0.50), 1),
        "p95": round(percentile(s, 0.95), 1),
        "p99": round(percentile(s, 0.99), 1),
        "max": round(s[-1], 1),
        "mean": round(statistics.mean(s), 1),
    }


def print_dist(name, d):
    if d is None:
        print(f"  {name:<28} [无样本]")
        return
    print(f"  {name:<28} n={d['n']:<4} P50={d['p50']:>7}  P95={d['p95']:>7}  "
          f"P99={d['p99']:>7}  max={d['max']:>7}  mean={d['mean']:>7}  {d['unit']}")


# ============================ HTTP 基础 ============================

def login(phone=None, pwd=None):
    r = requests.post(f"{GATEWAY}/accounts/login",
                      json={"cellPhone": phone or PHONE, "password": pwd or PASSWORD},
                      timeout=TIMEOUT)
    body = r.json()
    token = (body.get("data") or {}).get("accessToken")
    if not token:
        raise SystemExit(f"[FATAL] 登录失败：{json.dumps(body, ensure_ascii=False)[:200]}")
    return {"Authorization": f"Bearer {token}"}, token


def ws_connect(path, token, timeout=30):
    """经网关建立 WS 连接。

    三点必须照做，否则静默连错：
      1. token 只能走 **query 参数**（浏览器的 WS API 不能自定义请求头），
         网关 AuthGlobalFilter 会把它转成 user-info/role-info 头，
         judge-* 的 WsIdentityInterceptor 没有该头会直接 401 拒绝握手。
      2. token 必须作为**独立**参数拼接：写成 f"{path}?token=..." 时，
         若 path 自带 "?" 会拼成 `...?a=b?token=x`，token 根本没传进去（verify-p4 注释已记）。
      3. 握手完成 ≠ 已注册：CONNECTED / SNAPSHOT 由连接后异步推送。
    """
    sep = "&" if "?" in path else "?"
    return websocket.create_connection(f"{WS_BASE}{path}{sep}token={token}",
                                       timeout=timeout, suppress_origin=True)


def submit(headers, code, language, contest_id=0):
    r = requests.post(f"{GATEWAY}/submissions", headers=headers,
                      json={"problemId": PROBLEM, "contestId": contest_id,
                            "language": language, "code": code}, timeout=TIMEOUT)
    return r.json()


def wait_terminal(headers, sid, timeout_s=180):
    """轮询到终态。返回 (verdict, detail, elapsed_s)；elapsed 由调用方从提交前开始算。"""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        d = requests.get(f"{GATEWAY}/submissions/{sid}", headers=headers, timeout=TIMEOUT).json()
        data = d.get("data") or {}
        if data.get("verdict") in TERMINAL or data.get("status") == "FINISHED":
            return data.get("verdict"), data, None
        time.sleep(POLL_INTERVAL)
    return "TIMEOUT-WAIT", {}, None


# ============================ 各段落 ============================

def sec_judge(headers):
    """判题端到端耗时 —— 4 语言分别统计。"""
    print("\n" + "=" * 96)
    print(f"[A] 判题端到端耗时（问题 {PROBLEM}，每语言 {N} 个样本 + {WARMUP} 次预热）")
    print("=" * 96)
    out = {"problem": PROBLEM, "per_language": {}}

    for lang, tpl in CODE.items():
        e2e, sandbox, mems, verdicts = [], [], [], []
        total = WARMUP + N
        for i in range(total):
            code = render_nonce(tpl, i)
            t0 = time.time()
            body = submit(headers, code, lang)      # 已解析好的 JSON dict，勿再 .json()
            data = body.get("data") or {}
            sid = data.get("id")
            if body.get("code") != 200 or not sid:
                print(f"  [{lang}] 第 {i} 次受理失败：{json.dumps(body, ensure_ascii=False)[:140]}")
                continue
            verdict, detail, _ = wait_terminal(headers, sid)
            t1 = time.time()
            if i < WARMUP:                      # 预热样本丢弃（首次含镜像/类加载冷启动）
                continue
            verdicts.append(verdict)
            e2e.append((t1 - t0) * 1000.0)
            if detail.get("timeMs") is not None:
                sandbox.append(float(detail["timeMs"]))
            if detail.get("memoryKb") is not None:
                mems.append(float(detail["memoryKb"]))

        stat = {
            "samples": len(e2e),
            "ac_rate": round(sum(1 for v in verdicts if v == "AC") / len(verdicts), 4) if verdicts else None,
            "verdicts": {v: verdicts.count(v) for v in sorted(set(verdicts))},
            "judge_e2e_ms": describe(e2e),
            "sandbox_ms": describe(sandbox),
            "sandbox_memory_kb": describe(mems, "KB"),
        }
        out["per_language"][lang] = stat
        print(f"\n  [{lang}] verdict 分布 {stat['verdicts']}  AC 率={stat['ac_rate']}")
        print_dist("judge_e2e_ms（用户等待）", stat["judge_e2e_ms"])
        print_dist("sandbox_ms（程序本身）", stat["sandbox_ms"])
    return out


def sec_ws(headers, token):
    """WebSocket 终态推送延迟。

    竞态：提交接口先返回、我们才知道 sid，而判题**可能在握手完成前就已结束**。
    此时服务端按设计只补推一条 `SNAPSHOT(terminal=true)`（handler 注释「连接即推快照」），
    SUB_RESULT 永远不会再来。这类样本**没有推送延迟可测**，必须单独计入 `missed`，
    绝不能拿它去凑 push_latency（那会得到一个凭空缩短的假数字）。
    """
    print("\n" + "=" * 96)
    print(f"[B] WebSocket 终态推送延迟（{N} 个样本）")
    print("=" * 96)
    lat, e2e, errors = [], [], []
    missed = 0

    for i in range(N):
        t0 = time.time()
        body = submit(headers, render_nonce(CODE["PYTHON"], 10000 + i), "PYTHON")
        data = body.get("data") or {}
        sid = data.get("id")
        if not sid:
            errors.append(f"受理失败：{json.dumps(body, ensure_ascii=False)[:120]}")
            continue
        # 先提交再连（sid 未知）；判题本身秒级，连接握手远快于此
        try:
            ws = ws_connect(f"/ws/submissions/{sid}", token)
        except Exception as e:
            errors.append(f"{type(e).__name__}: {e}")
            continue
        ws.settimeout(1.0)
        deadline = time.time() + 120
        got_result = False
        types = []
        while time.time() < deadline:
            try:
                raw = ws.recv()
            except websocket.WebSocketTimeoutException:
                continue
            except Exception:
                break
            if not raw:
                break
            try:
                msg = json.loads(raw)
            except ValueError:
                continue
            types.append(msg.get("type"))
            if msg.get("type") == "SUB_RESULT":
                now_ms = time.time() * 1000.0
                e2e.append(now_ms - t0 * 1000.0)
                # 信封 ts 为服务端毫秒时间戳；同机同 OS 时钟源 → 单向延迟可估
                if msg.get("ts"):
                    lat.append(now_ms - float(msg["ts"]))
                got_result = True
                break
            # 连接时已终态：错过推送，无法测延迟 —— 记为 missed，不当错误也不当样本
            if msg.get("type") == "SNAPSHOT" and (msg.get("data") or {}).get("terminal"):
                missed += 1
                break
        try:
            ws.close()
        except Exception:
            pass
        if not got_result and not (types and types[-1] == "SNAPSHOT"):
            errors.append(f"sid={sid} 未在 120s 内收到 SUB_RESULT（frames={types[:6]}）")

    out = {
        "samples": len(lat),
        "missed_terminal_before_subscribe": missed,
        "push_latency_ms": describe(lat),
        "ws_e2e_ms": describe(e2e),
        "errors": errors[:10],
    }
    print_dist("push_latency_ms（服务端→本地）", out["push_latency_ms"])
    print_dist("ws_e2e_ms（提交→收到终态）", out["ws_e2e_ms"])
    print(f"  连接时已终态而错过推送的样本：{missed}（不计入 push_latency）")
    if errors:
        print(f"  ⚠ {len(errors)} 个样本失败，前 3 条：")
        for e in errors[:3]:
            print(f"     - {e}")
    print("  注：push_latency 为同机单向估算（跨进程但同一 OS 时钟源），不可当跨机网络延迟。")
    return out


def sec_rank(headers):
    """排行榜接口查询延迟。"""
    print("\n" + "=" * 96)
    print(f"[C] 排行榜查询延迟（{N} 次）")
    print("=" * 96)
    page = requests.get(f"{GATEWAY}/contests/page?pageNo=1&pageSize=20",
                        headers=headers, timeout=TIMEOUT).json()
    # 字段名是 `list` 不是 `records`：底座 PageDTO 把 MyBatis-Plus 的 records 改名成 list
    #   （judge-common/.../domain/PageDTO.java:19）。用错名字会静默拿到空列表 → 假 SKIP。
    records = ((page.get("data") or {}).get("list")) or []
    if not records:
        print("  ⚠ 无竞赛数据（/contests/page 的 data.list 为空）—— 榜单查询段跳过。")
        return {"samples": 0, "rank_query_ms": None, "errors": ["no contest"]}

    # 竞赛 id 是雪花 ID，接口以**字符串**返回（避免 JS 精度丢失），勿转 int。
    cid = records[0].get("id")
    lat, errors = [], []
    for _ in range(N):
        t0 = time.time()
        r = requests.get(f"{GATEWAY}/contests/{cid}/rank", headers=headers, timeout=TIMEOUT)
        lat.append((time.time() - t0) * 1000.0)
        if r.status_code != 200:
            errors.append(f"HTTP {r.status_code}")

    out = {
        "contestId": cid,
        "samples": len(lat),
        "rank_query_ms": describe(lat),
        "errors": errors[:5],
        "TODO": "榜单**更新**延迟需并发提交竞态场景，归入 perf-test 多实例对比轮次，见 docs/PERF.md",
    }
    print_dist("rank_query_ms", out["rank_query_ms"])
    print(f"  （contestId={cid}）")
    return out


def sec_ai(headers):
    """AI 点评首字节延迟（TTFT）。降级模式同样可测，但**不等于真实 LLM TTFT**。"""
    print("\n" + "=" * 96)
    print(f"[D] AI 点评首字节延迟 TTFT（{N} 次）")
    print("=" * 96)
    # 需要一个已出终态的提交作为点评对象
    body = submit(headers, render_nonce(CODE["PYTHON"], 20000), "PYTHON")
    sid = (body.get("data") or {}).get("id")
    if not sid:
        print("  ⚠ 无法创建用于点评的提交，跳过。")
        return {"samples": 0, "ttft_ms": None, "errors": ["no submission"]}
    wait_terminal(headers, sid)

    # 两套口径必须分开，否则数字没有意义：
    #   first_byte_ms —— 收到**第一个 SSE 字节**（即 START 事件）。START 在调 LLM **之前**
    #                    就下发（携带 reviewId/degraded/model），所以它快到 ~0，**不是 TTFT**。
    #   ttft_ms       —— 收到**首个 DELTA** 的时刻。这才是"首字延迟"。
    #   只看 first_byte 会把 TTFT 报小一个数量级，这是本段最容易踩的坑。
    first_byte, ttft, errors, degraded_seen = [], [], [], None
    for _ in range(N):
        t0 = time.time()
        try:
            r = requests.post(f"{GATEWAY}/ai/review/stream", headers=headers,
                              json={"submissionId": sid}, timeout=TIMEOUT, stream=True)
        except Exception as e:
            errors.append(f"{type(e).__name__}: {e}")
            continue
        if r.status_code != 200:
            errors.append(f"HTTP {r.status_code}")
            continue
        fb, td, buf = None, None, ""
        try:
            for chunk in r.iter_content(chunk_size=None, decode_unicode=True):
                if not chunk:
                    continue
                now = (time.time() - t0) * 1000.0
                if fb is None:
                    fb = now
                buf += chunk
                # 事件类型**不在** `event:` 里 —— Spring WebFlux 的 ServerSentEvent 统一写
                #   `event:message`，真正的类型在 data 的 JSON `type` 字段（START/RETRIEVAL/
                #   DELTA/END…）。按 `event:START` 去匹配会永远拿不到，TTFT 直接零样本。
                while "\n\n" in buf:
                    frame, buf = buf.split("\n\n", 1)
                    data = "".join(l[5:].strip() for l in frame.splitlines()
                                   if l.startswith("data:"))
                    name = ""
                    if data:
                        try:
                            name = (json.loads(data) or {}).get("type") or ""
                        except ValueError:
                            name = ""
                    if name == "DELTA" and td is None:
                        td = (time.time() - t0) * 1000.0
                    if name == "START" and degraded_seen is None:
                        degraded_seen = bool(re.search(r'"degraded"\s*:\s*true', data))
                    if name in ("END", "ERROR"):
                        buf = ""
                        break
        except Exception as e:
            errors.append(f"读流异常：{type(e).__name__}")
        finally:
            r.close()
        if fb is not None:
            first_byte.append(fb)
        if td is not None:
            ttft.append(td)

    out = {
        "submissionId": sid,
        "samples": len(ttft),
        "ttft_ms": describe(ttft),
        "first_byte_ms": describe(first_byte),
        "degraded_detected": degraded_seen,
        "errors": errors[:5],
        "NOTE": ("检测到降级流式（LLM 未配置）—— ttft_ms 是**降级回复的首个 DELTA**，"
                 "不是真实 LLM TTFT。配 CJ_LLM_ENABLED=true + CJ_LLM_API_KEY 后必须复跑。")
        if degraded_seen else "未检测到 degraded 标记（需人工确认是否走了真实 LLM）",
    }
    print_dist("ttft_ms（首个 DELTA）", out["ttft_ms"])
    print_dist("first_byte_ms（START 到达）", out["first_byte_ms"])
    print(f"  {out['NOTE']}")
    return out


def sec_workers(headers=None):
    """判题机集群只读快照 —— 1 vs 3 worker 对比的读数来源（对比本身靠两轮压测）。"""
    print("\n" + "=" * 96)
    print("[E] 判题机集群快照（只读，不压测）")
    print("=" * 96)
    if not headers:
        print("  [SKIP] 未提供管理员凭据（CJ_METRICS_ADMIN_PHONE/PASS）")
        return {"skipped": True, "reason": "no admin credentials"}
    w = requests.get(f"{GATEWAY}/workers", headers=headers, timeout=TIMEOUT).json()
    workers = w.get("data") or []
    m = requests.get(f"{GATEWAY}/workers/metrics", headers=headers, timeout=TIMEOUT).json()
    out = {"online_workers": len(workers), "workers": workers,
           "queue_metrics": m.get("data") or {}}
    print(f"  在线 worker 数 = {len(workers)}  （单实例基线应为 1；多实例对比需先起 9185/9285）")
    print(f"  队列指标 = {json.dumps(out['queue_metrics'], ensure_ascii=False)}")
    return out


# ============================ main ============================

SECTIONS = ["judge", "ws", "rank", "ai", "workers"]


def main():
    ap = argparse.ArgumentParser(description="CodeJudge 成果指标采集器")
    ap.add_argument("--section", action="append", choices=SECTIONS,
                    help="只跑指定段落，可重复；缺省跑全部（workers 需管理员凭据）")
    ap.add_argument("--n", type=int, default=None, help="覆盖每段样本数")
    ap.add_argument("--out", default=None, help="JSON 输出路径（默认 perf-test/results/metrics-<ts>.json）")
    args = ap.parse_args()

    global N
    if args.n:
        N = args.n

    sections = args.section or SECTIONS
    print("=" * 96)
    print("CodeJudge 指标采集 —— 数据来源为本机真实运行，禁止估算")
    print("=" * 96)
    print(f"网关 {GATEWAY}  问题 {PROBLEM}  每段样本 {N}")

    headers, token = login()
    print("学员登录 OK")

    result = {
        "collectedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "gateway": GATEWAY,
        "problem": PROBLEM,
        "n": N,
        "host": "本机单机（压测与服务抢同一 CPU；口径为容量下界，见 docs/PERF.md §1）",
    }

    if "judge" in sections:
        result["judge"] = sec_judge(headers)
    if "ws" in sections:
        result["ws"] = sec_ws(headers, token)
    if "rank" in sections:
        result["rank"] = sec_rank(headers)
    if "ai" in sections:
        result["ai"] = sec_ai(headers)
    if "workers" in sections:
        admin = None
        ap_phone, ap_pass = os.environ.get("CJ_METRICS_ADMIN_PHONE"), os.environ.get("CJ_METRICS_ADMIN_PASS")
        if ap_phone and ap_pass:
            admin, _ = login(ap_phone, ap_pass)
        result["workers"] = sec_workers(admin)

    # ---------------- 零样本自检（假绿比 FAIL 危险） ----------------
    empty = []
    fail_ac = []
    for key in ("judge", "ws", "rank", "ai"):
        sec = result.get(key)
        if not sec:
            continue
        if key == "judge":
            for lang, st in (sec.get("per_language") or {}).items():
                if not st.get("samples"):
                    empty.append(f"judge[{lang}]")
                # 有样本 ≠ 样本正确：模板正确但沙箱坏掉时会得到 10/10 RE，
                #    耗时分布照样"很好看"。AC 率为 0 必须当场暴露（假绿比 FAIL 危险）。
                elif not st.get("ac_rate"):
                    fail_ac.append(f"judge[{lang}] {st.get('verdicts')}")
        elif not sec.get("samples"):
            empty.append(key)

    out_path = pathlib.Path(args.out) if args.out else \
        RESULT_DIR / f"metrics-{time.strftime('%Y%m%d-%H%M%S')}.json"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    if fail_ac:
        result["acRateZero"] = fail_ac

    print("\n" + "=" * 96)
    print(f"结果已写入 {out_path}")
    if fail_ac:
        print(f"[FAIL] 以下语言 AC 率为 0（样本存在但全是错误结论，耗时数字不可用）：")
        for item in fail_ac:
            print(f"         {item}")
        print("       → 先查沙箱与用例 stderr（见技能 §13.15），别把 RE/CE 的耗时当判题耗时。")
    if empty:
        print(f"[FAIL] 以下段落零样本，数字不可用：{', '.join(empty)}")
        print("=" * 96)
        return 1
    if fail_ac:
        print("=" * 96)
        return 1
    print("[PASS] 全部段落均有样本，且四种语言 AC 率均 > 0")
    print("=" * 96)
    return 0


if __name__ == "__main__":
    sys.exit(main())
