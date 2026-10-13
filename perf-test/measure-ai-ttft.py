#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AI 代码点评接口首字延迟（TTFT）实测脚本。

测什么：judge-ai(9087, WebFlux SSE) 的流式点评 POST /ai/review/stream，
经网关 :9080（Bearer JWT）。SSE 事件序列 START → RETRIEVAL → DELTA×N → END。

口径（四个时刻，全部相对请求发出 t0）：
  headerMs  = 收到 HTTP 200 响应头（网关转发耗时，不含装配）
  ttfbMs    = 收到首个 SSE 字节（= START 事件；含 Feign 拉上下文 + 归属校验
              + pgvector 检索 + 落 pending 记录等「本服务开销」，不含 LLM）
  deltaMs   = 收到首个含正文的 DELTA 事件 —— **含公网 LLM 往返**，即用户感知 TTFT
  totalMs   = 收到 END（整段生成完成）

被测对象与前提（.env 读取，与 START 事件的 model 字段交叉核对）：
  CJ_LLM_BASE_URL / CJ_LLM_MODEL（DeepSeek 官方 API，OpenAI 兼容协议）。

RAG 分离口径：接口无 RAG 开关（cj.rag.* 仅有 top-k/chunk-size/并发上限），
有/无 RAG **无法分离**；每样本记录 RETRIEVAL 事件的 sources 数作为上下文证据。

样本构成（总 76 次 LLM 调用）：冒烟 1 + 1 用户串行 15 + 10 并发 10 + 50 并发 50。
每条样本 = 1 条新提交（Java A+B，nonce 防幂等）等 AC 后立即点评，
避免会话记忆（chatMemory 按提交累积）让样本互相污染。

用法：
  py -3 perf-test/measure-ai-ttft.py                     # 全量三组
  py -3 perf-test/measure-ai-ttft.py --serial 2 --skip-conc   # 冒烟

输出：控制台统计 + logs/ai-ttft-<时间戳>.json
"""

import argparse
import json
import math
import pathlib
import statistics
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import requests

ROOT = pathlib.Path(__file__).resolve().parent.parent
GATEWAY = "http://localhost:9080"
REVIEW_URL = f"{GATEWAY}/ai/review/stream"
PROBLEM_ID = 4001
LANGUAGE = "JAVA"
REVIEW_TYPE = 2  # 主动点评：不看判题结论，AC 提交也可评
RUN_TS = time.strftime("%Y%m%d-%H%M%S")

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
SUBMIT_GAP_S = 2.2          # 提交限流 30/min/人 的安全间隔


def _env_file_value(key: str) -> str:
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


def login(phone, password, tries=12):
    """登录拿 JWT；网关登录限流 2/s + burst 5，失败退避重试。"""
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


def submit_one(token, idx):
    code = JAVA_TMPL + f"// ai-ttft-{RUN_TS}-{idx}\n"
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


def wait_terminal(token, sid, timeout_s=150):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            d = requests.get(f"{GATEWAY}/submissions/{sid}",
                             headers={"Authorization": f"Bearer {token}"}, timeout=10).json()
            data = d.get("data") or {}
            if data.get("status") not in ("PENDING", "JUDGING", None):
                return data.get("verdict")
        except Exception:  # noqa: BLE001
            pass
        time.sleep(0.5)
    return None


def review_stream(token, sid):
    """一次 SSE 点评，返回时刻明细与事件摘要。"""
    rec = {"sid": sid, "httpErr": None, "events": [], "degraded": None, "model": None,
           "reviewId": None, "sourcesCount": None, "contentChars": 0,
           "finishReason": None, "errorCode": None, "errorMsg": None}
    t0 = time.time()
    try:
        r = requests.post(REVIEW_URL, headers={"Authorization": f"Bearer {token}",
                                               "Accept": "text/event-stream"},
                          json={"submissionId": sid, "reviewType": REVIEW_TYPE},
                          stream=True, timeout=(15, 300))
    except Exception as e:  # noqa: BLE001
        rec["httpErr"] = f"{type(e).__name__}: {e}"
        rec["t0"] = t0
        return rec
    rec["t0"] = t0
    rec["headerMs"] = round((time.time() - t0) * 1000, 1)  # 响应头到达
    if r.status_code != 200:
        rec["httpErr"] = f"HTTP {r.status_code}: {r.text[:200]}"
        return rec

    t_fb = t_delta = None
    buf = b""

    def handle_event(raw_line):
        nonlocal t_fb, t_delta
        s = raw_line.decode("utf-8", "replace")
        if not s.startswith("data:"):
            return
        try:
            ev = json.loads(s[5:].strip())
        except Exception:  # noqa: BLE001
            return
        etype = ev.get("type", "")
        if etype not in rec["events"]:
            rec["events"].append(etype)
        if etype == "START":
            rec["degraded"] = ev.get("degraded")
            rec["model"] = ev.get("model")
            rec["reviewId"] = ev.get("reviewId")
        elif etype == "RETRIEVAL":
            rec["sourcesCount"] = len(ev.get("sources") or [])
        elif etype == "DELTA":
            content = ev.get("content") or ""
            if content and t_delta is None:
                t_delta = time.time()
            rec["contentChars"] += len(content)
        elif etype == "ERROR":
            rec["errorCode"] = ev.get("code")
            rec["errorMsg"] = (ev.get("content") or "")[:150]
        elif etype == "END":
            rec["finishReason"] = ev.get("finishReason")

    try:
        for chunk in r.iter_content(chunk_size=None):
            if t_fb is None:
                t_fb = time.time()
                rec["ttfbMs"] = round((t_fb - t0) * 1000, 1)
            buf += chunk
            while b"\n\n" in buf:
                block, buf = buf.split(b"\n\n", 1)
                for ln in block.split(b"\n"):
                    handle_event(ln)
            if rec["finishReason"] is not None:
                break
    except Exception as e:  # noqa: BLE001
        rec["httpErr"] = f"stream {type(e).__name__}: {e}"
    finally:
        try:
            r.close()
        except Exception:  # noqa: BLE001
            pass
    rec["deltaMs"] = round((t_delta - t0) * 1000, 1) if t_delta else None
    rec["totalMs"] = round((time.time() - t0) * 1000, 1) if t_fb else None
    return rec


def run_sample(token, idx):
    """完整样本：提交 → 等 AC → SSE 点评。"""
    s = {"idx": idx, "submitErr": None, "verdict": None, "review": None}
    sid, err = submit_one(token, idx)
    s["submitErr"] = err
    if not sid:
        return s
    verdict = wait_terminal(token, sid)
    s["verdict"] = verdict
    if verdict != "AC":
        return s
    s["review"] = review_stream(token, sid)
    return s


def run_serial(token, n):
    print(f"\n[A组] 1 用户串行 × {n} 次点评……")
    out = []
    last_submit = 0.0
    for i in range(n):
        wait = SUBMIT_GAP_S - (time.time() - last_submit)
        if wait > 0:
            time.sleep(wait)
        t0 = time.time()
        s = run_sample(token, i)
        last_submit = time.time() - max((s["review"] or {}).get("totalMs", 0) / 1000, 0)
        rv = s["review"] or {}
        if s["submitErr"] or s["verdict"] != "AC" or rv.get("httpErr") or rv.get("errorCode"):
            print(f"  [{i + 1}/{n}] ⚠ sid={rv.get('sid')} submitErr={s['submitErr']} "
                  f"verdict={s['verdict']} httpErr={rv.get('httpErr')} "
                  f"ERROR={rv.get('errorCode')}:{rv.get('errorMsg')}")
        else:
            print(f"  [{i + 1}/{n}] sid={s['review']['sid']} reviewId={rv.get('reviewId')} "
                  f"ttfb={rv.get('ttfbMs')}ms 首DELTA={rv.get('deltaMs')}ms "
                  f"共{rv.get('contentChars')}字 {round(time.time() - t0, 1)}s")
        out.append(s)
    return out


def run_wave(tokens, size, label):
    """一波并发：错峰提交 → 全部等 AC → 栅栏对齐 → 同时发起点评。"""
    print(f"\n[{label}] {size} 账号同时点评（错峰提交 0.25s/个，等全部 AC 后栅栏对齐）……")
    barrier = threading.Barrier(size)
    sub_gap = [threading.Event() for _ in range(size)]

    def work(k):
        token = tokens[k]
        if k:
            sub_gap[k - 1].wait(timeout=15)  # 错峰提交，避免限流误伤
        s = {"idx": 10_000 + k, "submitErr": None, "verdict": None, "review": None}
        sid, err = submit_one(token, s["idx"])
        s["submitErr"] = err
        if sid:
            s["verdict"] = wait_terminal(token, sid)
        if k < size - 1:
            sub_gap[k].set()
        try:
            barrier.wait(timeout=300)
        except threading.BrokenBarrierError:
            s["review"] = None
            return s
        if s["verdict"] == "AC":
            s["review"] = review_stream(token, sid)
        return s

    with ThreadPoolExecutor(max_workers=size) as ex:
        out = list(ex.map(work, range(size)))
    ok = [s for s in out if s.get("review") and not s["review"].get("httpErr")
          and not s["review"].get("errorCode")]
    print(f"  点评成功 {len(ok)}/{size}")
    return out


def pct(sorted_vals, q):
    if not sorted_vals:
        return None
    idx = max(0, math.ceil(q * len(sorted_vals)) - 1)
    return sorted_vals[min(idx, len(sorted_vals) - 1)]


def summarize(name, samples):
    revs = [s["review"] for s in samples
            if s.get("review") and not s["review"].get("httpErr") and not s["review"].get("errorCode")]
    s = {"group": name, "samples": len(samples), "reviewOk": len(revs),
         "submitFailed": sum(1 for x in samples if x.get("submitErr")),
         "nonAc": sum(1 for x in samples if x.get("verdict") not in (None, "AC")),
         "httpFailed": sum(1 for x in samples if x.get("review") and x["review"].get("httpErr")),
         "errorEvents": sum(1 for x in samples if x.get("review") and x["review"].get("errorCode")),
         "degraded": sum(1 for x in samples if x.get("review") and x["review"].get("degraded"))}
    err_codes = [x["review"]["errorCode"] for x in samples
                 if x.get("review") and x["review"].get("errorCode")]
    if err_codes:
        s["errorCodes"] = {str(c): err_codes.count(c) for c in set(err_codes)}
    fins = [x["review"].get("finishReason") for x in samples
            if x.get("review") and x["review"].get("finishReason")]
    if fins:
        s["finishReasons"] = {str(f): fins.count(f) for f in set(fins)}
    # 生成中断（END.finishReason=ERROR）不影响 TTFT 有效性（首 DELTA 已发生），单列不剔除
    for key in ("headerMs", "ttfbMs", "deltaMs", "totalMs"):
        vals = sorted(r[key] for r in revs if r.get(key) is not None)
        s[f"{key}_n"] = len(vals)
        s[f"{key}_mean"] = round(statistics.fmean(vals), 1) if vals else None
        s[f"{key}_p50"] = pct(vals, 0.50)
        s[f"{key}_p95"] = pct(vals, 0.95)
        s[f"{key}_p99"] = pct(vals, 0.99)
        s[f"{key}_max"] = vals[-1] if vals else None
    srcs = [r["sourcesCount"] for r in revs if r.get("sourcesCount") is not None]
    if srcs:
        s["ragSources"] = {"min": min(srcs), "max": max(srcs), "mean": round(statistics.fmean(srcs), 1)}
    models = {r.get("model") for r in revs if r.get("model")}
    if models:
        s["models"] = sorted(models)
    if revs:
        print(f"\n[{name}] 样本={s['samples']} 点评成功={s['reviewOk']} "
              f"(提交失败={s['submitFailed']} 非AC={s['nonAc']} HTTP失败={s['httpFailed']} "
              f"ERROR事件={s['errorEvents']} 降级={s['degraded']})")
        print(f"  本服务开销(首SSE字节/START): n={s['ttfbMs_n']} 平均={s['ttfbMs_mean']}ms "
              f"P50={s['ttfbMs_p50']}ms P95={s['ttfbMs_p95']}ms P99={s['ttfbMs_p99']}ms")
        print(f"  含公网LLM往返(首DELTA):      n={s['deltaMs_n']} 平均={s['deltaMs_mean']}ms "
              f"P50={s['deltaMs_p50']}ms P95={s['deltaMs_p95']}ms P99={s['deltaMs_p99']}ms "
              f"最大={s['deltaMs_max']}ms")
        print(f"  整段生成(END): 平均={s['totalMs_mean']}ms P95={s['totalMs_p95']}ms")
        if s.get("ragSources"):
            print(f"  RAG证据: RETRIEVAL sources {s['ragSources']}")
    else:
        print(f"\n[{name}] ⚠ 无成功点评样本")
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", type=int, default=15, help="串行组样本数（默认 15）")
    ap.add_argument("--conc", type=int, default=50, help="最大并发档（默认 50）")
    ap.add_argument("--skip-conc", action="store_true", help="只跑串行组（冒烟用）")
    args = ap.parse_args()

    llm_base = _env_file_value("CJ_LLM_BASE_URL")
    llm_model = _env_file_value("CJ_LLM_MODEL")
    print("=" * 78)
    print(f"AI 点评 TTFT 实测 —— 网关 {GATEWAY}  SSE {REVIEW_URL}")
    print(f"LLM: {llm_base}  model={llm_model}（与 START 事件 model 交叉核对）")
    print(f"计划：串行 {args.serial} + 10 并发 + {args.conc} 并发"
          + ("（本轮仅串行冒烟）" if args.skip_conc else ""))
    print("=" * 78)

    need = 1 if args.skip_conc else max(10, args.conc)
    import csv
    with open(ROOT / "perf-test" / "csv" / "users.csv", newline="", encoding="utf-8") as f:
        users = [(r["cellPhone"].strip(), r["password"].strip())
                 for r in csv.DictReader(f)][:need]
    tokens = {}
    print(f"登录 {len(users)} 个账号（限流 2/s，间隔 0.6s）……")
    for phone, pwd in users:
        tokens[phone] = login(phone, pwd)
        time.sleep(0.6)

    # ── 冒烟/串行组 ──
    serial_n = 2 if args.skip_conc else args.serial
    serial_samples = run_serial(tokens[users[0][0]], serial_n)
    s_serial = summarize("serial-1user", serial_samples)

    # 冒烟先验证 LLM 真实可用（degraded/ERROR 则按约定停止）
    ok_revs = [s["review"] for s in serial_samples
               if s.get("review") and not s["review"].get("httpErr") and not s["review"].get("errorCode")]
    degraded = any(r.get("degraded") for r in ok_revs)
    if not ok_revs or degraded:
        print("\n⚠ 冒烟未获得真实 LLM 流（ degraded={} 成功={} ）—— 按 LLM 配额/服务异常处理，停止实测"
              .format(degraded, len(ok_revs)))
        args.skip_conc = True

    s_c10 = s_c50 = None
    c10_samples = c50_samples = []
    if not args.skip_conc:
        c10_samples = run_wave([tokens[p] for p, _ in users[:10]], 10, "B组-10并发")
        s_c10 = summarize("conc-10users", c10_samples)
        c50_samples = run_wave([tokens[p] for p, _ in users[:args.conc]], args.conc, "C组-50并发")
        s_c50 = summarize(f"conc-{args.conc}users", c50_samples)

    out = ROOT / "logs" / f"ai-ttft-{RUN_TS}.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps({
        "meta": {
            "ts": RUN_TS,
            "target": "judge-ai 流式点评 POST /ai/review/stream（经网关 :9080，Bearer JWT）",
            "sseEvents": "START → RETRIEVAL → DELTA×N → END（错误走 ERROR 事件，HTTP 恒 200）",
            "latencyDef": "headerMs=响应头；ttfbMs=首SSE字节(START，本服务开销含装配/检索，不含LLM)；"
                          "deltaMs=首个含正文DELTA（含公网LLM往返，用户感知TTFT）；totalMs=END",
            "llm": {"baseUrl": llm_base, "model": llm_model,
                    "protocol": "OpenAI 兼容（judge-ai LlmClient WebClient SSE）"},
            "rag": "接口无 RAG 开关（cj.rag.* 仅 top-k/chunk-size/max-concurrent-streams），"
                   "有/无 RAG 无法分离；每样本 RETRIEVAL sources 数为上下文证据",
            "sampleDesign": "每样本 = 1 条新提交(Java A+B, nonce 防幂等) 等 AC 后点评，"
                            "避免 chatMemory 会话记忆污染样本；REVIEW_TYPE=2 主动点评",
            "accounts": "perf-test/csv/users.csv（13950000000+N / 123456）",
        },
        "summary": {"serial": s_serial, "conc10": s_c10, f"conc{args.conc}": s_c50},
        "samples": {"serial": serial_samples, "conc10": c10_samples, f"conc{args.conc}": c50_samples},
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n明细已写入 {out.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
