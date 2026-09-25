#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""告警阈值重标工具 —— 用**实测分位数**推导阈值，而不是拍数字。

用法：
    python scripts/recalibrate-alerts.py                          # 只打印建议（读本地 Prometheus）
    python scripts/recalibrate-alerts.py --prometheus http://prom:9090
    python scripts/recalibrate-alerts.py --write                  # 按建议改写规则文件
    python scripts/recalibrate-alerts.py --margin 4 --write

与规则文件的契约：
    `deploy/monitoring/prometheus/rules/codejudge-alerts.yml` 中每个可标定阈值都带一行锚点：
        # @tune <name> <当前值>
    脚本据此**精确定位**并替换紧随其后的第一个 `> <当前值>`，同时把锚点里的值更新为新值。
    锚点缺失或找不到对应比较式 → 直接报错退出，**绝不静默跳过**。
    （静默跳过会让「重标」变成什么都不做，而输出看起来是成功的 —— 这是本脚本最想避免的失败模式。）

为什么必须排除 /actuator 与 SSE：
    见规则文件顶部「阈值标定记录」。简言之，Prometheus 抓取 /actuator/prometheus 时，
    这次抓取本身会被 Micrometer 计入 http_server_requests，而它要序列化几百 KB 文本，
    实测把 judge-problem 的 P95 从 6ms 抬到 78ms（13×）。不排除就不是业务延迟。
"""
from __future__ import annotations

import argparse
import json
import math
import pathlib
import re
import sys
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
RULES = ROOT / "deploy/monitoring/prometheus/rules/codejudge-alerts.yml"

# 业务口径：排除自监控端点与 SSE 长连接
BIZ = 'uri!~"/actuator.*|/ai/review/stream.*"'


# ----------------------------------------------------------------- Prometheus

def query(prom: str, expr: str, timeout: float = 15.0) -> list[dict]:
    url = f"{prom.rstrip('/')}/api/v1/query?" + urllib.parse.urlencode({"query": expr})
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            d = json.loads(r.read().decode("utf-8", "replace"))
    except Exception as e:
        print(f"[WARN] 查询失败（{e}）：{expr[:80]}...", file=sys.stderr)
        return []
    if d.get("status") != "success":
        print(f"[WARN] 查询非成功：{d.get('error')}", file=sys.stderr)
        return []
    return d["data"]["result"]


def baseline(prom: str) -> dict:
    """采集基线。返回各项原始观测值。"""
    out: dict = {}

    # 1) 业务接口 P95（按 uri），排除 actuator 与 SSE
    rows = query(prom, f'histogram_quantile(0.95, sum by (application, uri, le) '
                       f'(rate(http_server_requests_seconds_bucket{{job="codejudge", {BIZ}}}[5m])))')
    items = []
    dropped = []
    for r in rows:
        v = float(r["value"][1])
        uri = r["metric"].get("uri", "?")
        if math.isnan(v):
            # histogram_quantile 在样本稀疏时返回 NaN。而**稀疏的恰恰可能是最慢的接口**：
            #   /accounts/login 每次登录都有 BCrypt 开销（实测 P95 110–139ms），
            #   但压测里登录走 Once Only Controller，5m 窗口内只有几十个样本 → 被判 NaN 丢掉。
            #   丢掉它会让"最慢接口"退化成 /problems/{id}（80ms），基线因此**偏低**，
            #   据此重标会把阈值收紧到不该有的水平。故单独记账，由 recommend() 决定是否采纳。
            dropped.append(uri)
        else:
            items.append((uri, v))
    items.sort(key=lambda x: -x[1])
    out["p95_uri"] = items
    out["p95_max"] = items[0][1] if items else None
    out["p95_dropped"] = dropped

    # 2) 提交 QPS（判题入口）
    #
    # 必须同时限定 method=POST 与 uri 精确匹配。旧写法是
    #     uri=~"/submissions.*"
    #   它把**只读接口 `/submissions/page`（提交记录列表）与 `/submissions/{id}`（详情）
    #   也算成了"提交"**。2026-09-21 实测：压测刚结束时该表达式给出 323.6 req/s，
    #   而同期真实的 `POST /submissions` 是 **0.000 req/s** —— 323.6 全部来自
    #   `GET /submissions/page`（264.1 req/s 的列表页读流量）的 5m 滑窗残留。
    #   后果不是"数字不准"这么轻：`submit_fail_rps` 会被抬高两个数量级
    #   （0.5 → 3.24），等于把「提交失败突增」这条告警阈值放宽到基本不会触发 ——
    #   判题入口挂了都不报警。所以这里宁可写死得窄一点。
    r = query(prom, f'sum(rate(http_server_requests_seconds_count{{job="codejudge", '
                    f'application="judge-submission", method="POST", uri="/submissions"}}[5m]))')
    out["submit_qps"] = float(r[0]["value"][1]) if r else 0.0

    # 3) 在线判题机数
    r = query(prom, 'judge_workers_online{job="codejudge"}')
    out["workers"] = float(r[0]["value"][1]) if r else 0.0

    # 4) 当前积压（参考）
    r = query(prom, 'judge_queue_backlog{job="codejudge"}')
    out["backlog"] = float(r[0]["value"][1]) if r else 0.0

    return out


# ----------------------------------------------------------------- 阈值推导

def recommend(base: dict, *, margin: float, budget: float,
              worker_tps: float, wait_s: float) -> dict:
    """由基线推导建议阈值。每项都返回 (建议值, 依据说明)。"""
    rec: dict[str, tuple[float, str]] = {}

    # 延迟：最慢业务接口 × 余量系数，向上取整到 0.05s
    #
    # 但只要窗口内有 NaN（样本稀疏）被丢弃，就**不采纳**这次推导 ——
    #   丢掉稀疏接口（通常是 BCrypt 登录这种"调用少但天生慢"的）会让基线偏低，
    #   据此收紧阈值等于凭不完整的数据把告警调紧，比不调更糟。
    #   宁可不改：等压测把登录也打足样本，或在独立压测机上重标。
    dropped = base.get("p95_dropped") or []
    if base.get("p95_max") and not dropped:
        raw = base["p95_max"] * margin
        val = math.ceil(raw / 0.05) * 0.05
        slowest = base["p95_uri"][0]
        rec["latency_p95_seconds"] = (
            val,
            f"最慢业务接口 {slowest[0]} 实测 P95={slowest[1]*1000:.0f}ms × {margin}(余量) "
            f"= {raw*1000:.0f}ms → 取整 {val*1000:.0f}ms")
    elif dropped:
        rec["latency_p95_seconds"] = (
            0.3,
            f"窗口内 {len(dropped)} 个接口 P95 为 NaN（样本稀疏）已排除："
            f"{', '.join(dropped[:3])}{' 等' if len(dropped) > 3 else ''} —— "
            f"基线可能偏低，沿用当前值 0.3；样本补足后在独立压测机重标")
    else:
        rec["latency_p95_seconds"] = (0.3, "无业务流量样本，沿用当前值")

    # 错误率：直接取错误预算
    rec["error_rate"] = (budget, f"错误预算（SLO）{budget:.0%}")

    # 提交失败突增：提交 QPS × 错误预算，保守下限 0.5
    if base.get("submit_qps", 0) > 0:
        raw = base["submit_qps"] * budget
        val = max(round(raw, 2), 0.5)
        rec["submit_fail_rps"] = (
            val, f"提交 QPS {base['submit_qps']:.2f} × {budget:.0%} = {raw:.3f} → 取 max(·, 0.5) = {val}")
    else:
        rec["submit_fail_rps"] = (0.5, "无提交流量样本，沿用当前值")

    # 队列积压：实例数 × 单实例吞吐 × 可接受排队时长（取整到 10，避免 195 这类噪声值）
    workers = max(base.get("workers", 0), 1)
    val = round(workers * worker_tps * wait_s / 10) * 10
    rec["queue_backlog"] = (
        val, f"{workers:.0f} 实例 × {worker_tps} 题/秒 × {wait_s:.0f}s 可接受排队 = {val}")

    # 资源类不随容量重标，保持现值（显式说明，避免读者以为漏了）
    rec["jvm_heap_ratio"] = (0.85, "通用经验线（85% 起 GC 显著影响尾延迟），不随容量变")
    # 连接池判据已于 2026-09-21 从 `pending > 5` 换成 `平均获取连接耗时 > 0.2s`：
    # 实测 3100 req/s 的**健康态**下 pending 也会到 29，用它等于给正常系统报 warning。
    # 「取连接等了多久」才与用户可见劣化单调相关，正常值 ~7.6ms，故 0.2s 是 26× 余量。
    rec["hikari_acquire_avg"] = (
        0.2, "实测健康态平均获取连接耗时 ~7.6ms，0.2s 为 26× 余量（pending 已弃用为非故障信号）")
    rec["process_cpu_ratio"] = (0.9, "通用经验线，不随容量变")
    return rec


def fmt(v: float) -> str:
    if isinstance(v, float) and v == int(v) and abs(v) >= 1:
        return str(int(v))
    return f"{v:g}"


# ----------------------------------------------------------------- 改写规则

ANCHOR = re.compile(r"^(\s*)#\s*@tune\s+([a-z0-9_]+)\s+([\d.]+)\s*$", re.M)


def apply_tunes(text: str, rec: dict[str, tuple[float, str]]) -> tuple[str, list[str]]:
    """按锚点替换阈值。缺失锚点 → 抛异常（不静默跳过）。"""
    changed: list[str] = []
    anchors = {m.group(2): m for m in ANCHOR.finditer(text)}
    missing = [k for k in rec if k not in anchors]
    if missing:
        raise KeyError(f"规则文件缺少锚点 # @tune: {', '.join(missing)}")

    # 从后往前替换，避免位置偏移
    for name in sorted(anchors, key=lambda n: -anchors[n].start()):
        if name not in rec:
            continue
        new_val, _ = rec[name]
        m = anchors[name]
        old_val = m.group(3)
        new_str = fmt(new_val)
        if old_val == new_str:
            continue
        # 锚点之后的第一个 "> <old>"
        tail_start = m.end()
        tail = text[tail_start:]
        needle = f"> {old_val}"
        idx = tail.find(needle)
        if idx < 0:
            raise ValueError(
                f"锚点 @tune {name} 之后找不到 `{needle}` —— "
                f"表达式可能被改动过，请人工核对 {RULES.name}")
        # 边界保护：确保不是 "> 0.85" 命中 "> 0.8" 之类的前缀
        after = tail[idx + len(needle): idx + len(needle) + 1]
        if after and (after.isdigit() or after == "."):
            raise ValueError(f"锚点 @tune {name} 的 `{needle}` 匹配到更长数字，请人工核对")
        tail = tail[:idx] + f"> {new_str}" + tail[idx + len(needle):]
        # 同步更新锚点自身的值
        text = text[:m.start(3)] + new_str + tail
        changed.append(f"{name}: {old_val} → {new_str}")
    return text, changed


# ----------------------------------------------------------------- 入口

def main() -> int:
    ap = argparse.ArgumentParser(
        description="用实测分位数重标 Prometheus 告警阈值",
        formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    ap.add_argument("--prometheus", default="http://127.0.0.1:9090",
                    help="Prometheus 地址（默认 http://127.0.0.1:9090）")
    ap.add_argument("--margin", type=float, default=3.0,
                    help="延迟余量系数（阈值 = 实测最慢 P95 × margin，默认 3）")
    ap.add_argument("--error-budget", type=float, default=0.01,
                    help="错误预算 / 可接受错误率（默认 0.01 即 1%%）")
    ap.add_argument("--worker-tps", type=float, default=1.5,
                    help="单实例判题吞吐（题/秒，默认 1.5 —— 本项目实测值）")
    ap.add_argument("--queue-wait-seconds", type=float, default=130,
                    help="可接受的排队时长（秒，默认 130）")
    ap.add_argument("--write", action="store_true", help="改写规则文件（默认只打印建议）")
    ap.add_argument("--file", default=str(RULES), help="规则文件路径")
    args = ap.parse_args()

    path = pathlib.Path(args.file)
    if not path.exists():
        print(f"[FATAL] 找不到规则文件：{path}", file=sys.stderr)
        return 2

    print("=" * 78)
    print(f"告警阈值重标 ｜ Prometheus {args.prometheus}")
    print("=" * 78)

    base = baseline(args.prometheus)

    print("\n--- 基线（业务口径：已排除 /actuator 与 SSE）---")
    if base["p95_uri"]:
        for uri, v in base["p95_uri"][:10]:
            print(f"  P95 {uri:<40} {v*1000:>9.1f} ms")
    else:
        print("  （无业务 P95 样本 —— 可能刚启动/无流量；延迟阈值将沿用当前值）")
    print(f"  提交 QPS={base['submit_qps']:.3f}  在线判题机={base['workers']:.0f}  "
          f"当前积压={base['backlog']:.0f}")
    if base.get("p95_dropped"):
        print(f"  ⚠ 已排除 {len(base['p95_dropped'])} 个样本稀疏的接口（P95=NaN）："
              f"{', '.join(base['p95_dropped'][:4])}"
              f"{' 等' if len(base['p95_dropped']) > 4 else ''}")
        print("    这些接口往往天生慢（如 BCrypt 登录），排除后基线偏低 → 本次不改延迟阈值")

    rec = recommend(base, margin=args.margin, budget=args.error_budget,
                    worker_tps=args.worker_tps, wait_s=args.queue_wait_seconds)

    print("\n--- 建议阈值 ---")
    print(f"  {'锚点名':<24}{'建议值':>10}   依据")
    print("  " + "-" * 74)
    for name, (val, why) in rec.items():
        print(f"  {name:<24}{fmt(val):>10}   {why}")

    text = path.read_text(encoding="utf-8", errors="replace")
    try:
        new_text, changed = apply_tunes(text, rec)
    except (KeyError, ValueError) as e:
        print(f"\n[FATAL] {e}", file=sys.stderr)
        return 2

    print("\n--- 变更 ---")
    if not changed:
        print("  无需变更（当前值与建议一致）。")
        return 0
    for c in changed:
        print(f"  · {c}")

    if not args.write:
        print("\n（预演模式，未改动文件。确认后加 --write）")
        return 0

    path.write_text(new_text, encoding="utf-8", newline="")
    print(f"\n✅ 已写入 {path}")
    print("   生效方式：curl -X POST http://<prometheus>:9090/-/reload")
    print("   校验语法：docker run --rm -v \"$PWD/deploy/monitoring/prometheus/rules:/rules:ro\""
          " \\\n             --entrypoint=promtool prom/prometheus:v2.54.1"
          " check rules //rules/codejudge-alerts.yml")
    return 0


if __name__ == "__main__":
    sys.exit(main())
