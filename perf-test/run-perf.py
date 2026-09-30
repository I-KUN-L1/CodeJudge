#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
JMeter 无 GUI 运行器 + 结果解读。

它做四件事，缺一不可：
  1. 找 JMeter（环境变量 JMETER_HOME > D:\\1\\jmeter > PATH），避免写死路径；
  2. 确保账号池存在（缺失则自动播种），否则跑出来的是一堆 401，浪费时间；
  3. 以 -n 模式跑 JMX，同时产出 .jtl（原始数据）与 HTML 报告（给人看）；
  4. **解析 .jtl 自己算指标并做门槛判定** —— JMeter 的 HTML 报告只展示不打分，
     没有判定就无法用于 CI/验收。本脚本按 README 里的目标给出 PASS/FAIL。

用法：
    python perf-test/run-perf.py                      # 跑 load 计划（默认参数）
    python perf-test/run-perf.py --plan smoke
    python perf-test/run-perf.py --plan throughput    # 恒定负载，唯一做吞吐判定的计划
    python perf-test/run-perf.py --plan load -J tgSubmit.threads=40 -J tgBrowse.threads=60
    python perf-test/run-perf.py --plan load --max-error-rate 0.02
    python perf-test/run-perf.py --plan load --no-html      # 只跑 + 判定，不生成 HTML

被测目标地址（独立压测机执行时必设）：
    CJ_PERF_HOST / CJ_PERF_PORT / CJ_PERF_PROTOCOL  —— 默认 127.0.0.1 / 9080 / http。
    不设就会打压测机自己。详见 perf-test/RUNBOOK.md。

退出码：0 = 全部达标；1 = 有指标未达标；2 = 环境/执行错误。
"""
import argparse
import csv
import os
import pathlib
import shutil
import statistics
import subprocess
import sys
import time

HERE = pathlib.Path(__file__).parent
ROOT = HERE.parent
JMX_DIR = HERE / "jmx"
RESULT_DIR = HERE / "results"
CSV_PATH = HERE / "csv" / "users.csv"

# ---------------------------------------------------------------
# 门槛（与 README「预期指标」表一一对应；--max-* 可覆盖）
# key = JMeter 里的 sampler label 前缀
# ---------------------------------------------------------------
THRESHOLDS = [
    # (sampler 标签, P95 上限 ms, 允许错误率)
    ("POST /accounts/login",      500,  0.01),
    ("GET /problems/page",        300,  0.01),
    ("GET /problems/{id}",        300,  0.01),
    ("POST /submissions",         800,  0.01),
    ("GET /submissions/page",     400,  0.01),
    ("GET /contests/{id}/rank",   300,  0.01),
    ("GET /submissions/{id}",     400,  0.01),
    ("GET /contests/page",        300,  0.01),
]
MIN_THROUGHPUT = 500.0      # req/s，@ 默认 100 并发

# 各计划 jmx 里未覆盖时的默认 duration（秒）—— 看门狗兜底用。
# soak.jmx 的默认时长是 3600，若不在此登记，看门狗会按 180s 兜底把 1h 长稳提前杀掉。
PLAN_DEFAULT_DURATION = {"smoke": 60, "load": 180, "throughput": 180, "soak": 3600}


def find_jmeter():
    for cand in [os.environ.get("JMETER_HOME"),
                 r"D:\1\jmeter",
                 r"C:\apache-jmeter-5.6.3"]:
        if cand and (pathlib.Path(cand) / "bin" / "jmeter.bat").exists():
            return pathlib.Path(cand) / "bin" / "jmeter.bat"
    found = shutil.which("jmeter") or shutil.which("jmeter.bat")
    if found:
        return pathlib.Path(found)
    print("[FATAL] 找不到 JMeter。设置 JMETER_HOME，或确认 D:\\1\\jmeter\\bin\\jmeter.bat 存在。",
          file=sys.stderr)
    sys.exit(2)


def ensure_user_pool():
    if CSV_PATH.exists() and CSV_PATH.stat().st_size > 0:
        with CSV_PATH.open(encoding="utf-8") as f:
            n = sum(1 for _ in f) - 1
        print(f"[账号池] 复用已有 {CSV_PATH.name}（{n} 个账号）")
        return
    print("[账号池] 未发现，开始自动播种 ...")
    rc = subprocess.run([sys.executable, str(HERE / "seed-users.py"), "--count", "120"]).returncode
    if rc != 0:
        print("[FATAL] 账号池播种失败，压测中止。", file=sys.stderr)
        sys.exit(2)


def _kill_tree(proc):
    """连同子进程一起杀 —— JMeter 的启动脚本会再起一个 JVM，
    只杀父进程会留下一个**继续压测**的孤儿 java（实测踩过）。"""
    if os.name == "nt":
        subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    else:
        try:
            os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
        except Exception:
            proc.kill()


def watchdog_seconds(plan, extra_j):
    """JMeter 的最长墙钟上限。

    为什么必须有：计划里的 `ThreadGroup.duration` 一旦没生效（例如 scheduler 被
    `<boolProp>` 的函数替换问题判成 false），「无限循环 + 无调度器」就是**永不结束**。
    实测过一次：配 180s 的计划跑了 13 分钟、226 万样本、.jtl 涨到 400MB 仍不停，
    把机器吃得只剩骨架，而且压测结论全部作废。运行器不该有这种无界失败模式。

    取值 = max(各线程组 duration) + 180s 余量（JMeter 启动 + 报告生成）。
    可用 CJ_PERF_MAX_WALL 直接覆盖（单位秒）。
    """
    override = os.environ.get("CJ_PERF_MAX_WALL")
    if override:
        try:
            return max(30, int(override))
        except ValueError:
            pass
    durations = []
    for kv in extra_j:
        if kv.startswith("tg") and ".duration=" in kv:
            try:
                durations.append(int(kv.split("=", 1)[1]))
            except ValueError:
                pass
    # 命令行没显式覆盖 duration 时，按计划的 jmx 默认值兜底（soak=3600）
    if not durations:
        durations = [PLAN_DEFAULT_DURATION.get(plan, 180)]
    return max(durations) + 180


def run_jmeter(jmeter, plan, extra_j, report_dir, jtl, html=True):
    jmx = JMX_DIR / f"{plan}.jmx"
    if not jmx.exists():
        print(f"[FATAL] 测试计划不存在：{jmx}", file=sys.stderr)
        sys.exit(2)

    # HTML 报告目录必须是空的，否则 JMeter 直接拒绝生成。
    # 报告目录有上千个文件，`rmtree` 属于批量删除 —— 在带删除保护的环境里会被拦下，
    #    导致「跑不了压测」。因此加 --no-html：门槛判定只依赖 .jtl 解析，
    #    HTML 报告纯属给人看的产物，不该成为必经路径。
    if html:
        if report_dir.exists():
            shutil.rmtree(report_dir)
        report_dir.parent.mkdir(parents=True, exist_ok=True)
    if jtl.exists():
        jtl.unlink()

    # 被测目标地址：默认本机网关。**独立压测机**跑生产压测时必须覆盖，
    # 否则会去打压测机自己（表现为瞬时全连接被拒，且数字"好看得离谱"）。
    # 用环境变量而不是命令行，是为了让同一套命令在两种环境里都不必改写；
    # 末尾的 extra_j 仍可覆盖（JMeter 的 -J 后出现的生效）。
    host = os.environ.get("CJ_PERF_HOST", "127.0.0.1")
    port = os.environ.get("CJ_PERF_PORT", "9080")
    protocol = os.environ.get("CJ_PERF_PROTOCOL", "http")

    cmd = [str(jmeter), "-n",
           "-t", str(jmx),
           "-l", str(jtl),
           "-j", str(RESULT_DIR / f"{plan}-jmeter.log"),
           # 让 jtl 带表头 + CSV 格式：解析脚本据此定位列，不靠猜列序
           "-Jjmeter.save.saveservice.output_format=csv",
           "-Jjmeter.save.saveservice.print_field_names=true",
           "-Jusers.csv=" + str(CSV_PATH).replace("\\", "/"),
           f"-Jhost={host}", f"-Jport={port}", f"-Jprotocol={protocol}"]
    if html:
        cmd += ["-e", "-o", str(report_dir)]
    for kv in extra_j:
        cmd += ["-J" + kv]

    print(f"[执行] {' '.join(cmd[:1])} -n -t {jmx.name} {' '.join('-J' + k for k in extra_j)}")
    kill_after = watchdog_seconds(plan, extra_j)
    print(f"[看门狗] 最长墙钟 {kill_after}s，超时强制终止整棵进程树")
    t0 = time.time()
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            # POSIX 下另起进程组，才能整组 kill（否则只杀到 shell）
                            start_new_session=(os.name != "nt"))
    timed_out = False
    try:
        out = proc.communicate(timeout=kill_after)[0].decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        timed_out = True
        _kill_tree(proc)
        out = proc.communicate()[0].decode("utf-8", "replace")
    dur = time.time() - t0

    if timed_out:
        print(f"\n[FATAL] JMeter 超过看门狗上限 {kill_after}s 仍未结束，已强制终止。", file=sys.stderr)
        print("        常见原因：计划里的 ThreadGroup.duration 没生效，导致"
              "「无限循环 + 调度器未开」永不停止。", file=sys.stderr)
        print("        检查点：<boolProp name=\"ThreadGroup.scheduler\"> 必须是**字面量** true——",
              file=sys.stderr)
        print("        JMeter 的 <boolProp> 不做函数替换，写成 ${__P(...)} 会被判成 false。",
              file=sys.stderr)
        print("        同时确认 <stringProp name=\"ThreadGroup.duration\"> 能正常展开。",
              file=sys.stderr)
        print(f"        本次已产生的 .jtl：{jtl}（可手工解析，但样本数不代表稳态窗口）",
              file=sys.stderr)
        sys.exit(2)

    # 只回显关键行，避免刷屏（JMeter 的输出里 90% 是无用进度）
    for line in out.splitlines():
        s = line.strip()
        if s.startswith("summary") or "Err:" in s or "error" in s.lower():
            print("   " + s)
    if proc.returncode != 0:
        print("[FATAL] JMeter 退出码非 0，末尾输出：", file=sys.stderr)
        print("\n".join(out.splitlines()[-25:]), file=sys.stderr)
        sys.exit(2)
    print(f"[完成] 耗时 {dur:.1f}s，原始数据 {jtl.name}")
    return dur


def percentile(sorted_vals, p):
    """线性插值分位数。JMeter 的 HTML 报告用同一算法，这样两边数字能对上。"""
    if not sorted_vals:
        return 0.0
    k = (len(sorted_vals) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(sorted_vals) - 1)
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (k - lo)


def parse_jtl(jtl):
    """按 sampler 标签聚合。返回 {label: stats}"""
    per = {}
    with jtl.open(encoding="utf-8", errors="replace") as f:
        reader = csv.DictReader(f)
        if reader.fieldnames is None or "elapsed" not in (reader.fieldnames or []):
            f.seek(0)
            f.readline()                       # 无表头 → 跳过第一行（不是表头就是数据，代价可接受）
            cols = ["timeStamp", "elapsed", "label", "responseCode", "responseMessage",
                    "threadName", "dataType", "success", "failureMessage", "bytes",
                    "sentBytes", "grpThreads", "allThreads", "URL", "Latency", "IdleTime", "Connect"]
            reader = csv.DictReader(f, fieldnames=cols)
        for row in reader:
            if not row.get("elapsed"):
                continue
            st = per.setdefault(row["label"], {"elapsed": [], "ok": 0, "bad": 0,
                                               "first": None, "last": None})
            try:
                st["elapsed"].append(float(row["elapsed"]))
            except ValueError:
                continue
            ok = str(row.get("success", "")).strip().lower() == "true"
            st["ok" if ok else "bad"] += 1
            ts = float(row.get("timeStamp") or 0)
            st["first"] = ts if st["first"] is None else min(st["first"], ts)
            st["last"] = ts if st["last"] is None else max(st["last"], ts)
    return per


def report(per, wall, plan):
    if not per:
        print("[FATAL] .jtl 无有效样本 —— 检查是否所有线程都因拿不到 token 直接失败。", file=sys.stderr)
        sys.exit(2)

    total = sum(s["ok"] + s["bad"] for s in per.values())
    bad = sum(s["bad"] for s in per.values())
    span = (max(s["last"] for s in per.values()) - min(s["first"] for s in per.values())) / 1000.0 or wall
    overall_tps = total / span

    print()
    print("=" * 104)
    print("采样结果")
    print("=" * 104)
    print(f"{'sampler':<30}{'样本':>9}{'错误':>8}{'错误率':>10}"
          f"{'平均ms':>10}{'P50':>9}{'P95':>9}{'P99':>9}{'max':>9}{'req/s':>10}")
    print("-" * 104)
    for label in sorted(per):
        s = per[label]
        vals = sorted(s["elapsed"])
        n = len(vals)
        tps = n / span
        err = s["bad"] / max(n, 1)
        print(f"{label:<30}{n:>9}{s['bad']:>8}{err:>10.3%}"
              f"{statistics.mean(vals):>10.1f}"
              f"{percentile(vals, 0.50):>9.1f}{percentile(vals, 0.95):>9.1f}"
              f"{percentile(vals, 0.99):>9.1f}{max(vals):>9.1f}{tps:>10.1f}")
    print("-" * 104)
    print(f"{'合计':<30}{total:>9}{bad:>8}{bad / max(total, 1):>10.3%}"
          f"{'':>10}{'':>9}{'':>9}{'':>9}{'':>9}{overall_tps:>10.1f}")
    print(f"\n有效压测窗口 {span:.1f}s（按样本时间戳跨度计，比进程墙钟更准——不含 JMeter 启停）")

    # ---------------- 门槛判定 ----------------
    print()
    print("=" * 104)
    print("门槛判定")
    print("=" * 104)
    fails = []
    for label, p95_max, err_max in THRESHOLDS:
        s = per.get(label)
        if not s:
            print(f"  [SKIP] {label:<30} 本次未采集到样本")
            continue
        vals = sorted(s["elapsed"])
        p95 = percentile(vals, 0.95)
        err = s["bad"] / max(len(vals), 1)
        ok = p95 <= p95_max and err <= err_max
        mark = "PASS" if ok else "FAIL"
        if not ok:
            fails.append(label)
        print(f"  [{mark}] {label:<30} P95={p95:>8.1f}ms (<= {p95_max})   "
              f"错误率={err:>7.3%} (<= {err_max:.2%})")

    # 吞吐门槛**只对恒定负载模型成立**：
    #   · smoke       —— 5 线程的正确性计划，每 sampler 仅 10 个样本，吞吐天然个位数。
    #                    拿 500 req/s 去判它必然「未达标」，是门槛误用（此前会稳定输出假 FAIL）。
    #   · load        —— **固定工作量**模型：总请求数由循环数决定，跑完即止。
    #                    观测吞吐的算术上限 ≈ 总请求数 / ramp 时长，
    #                    实测已证实：工作量 ×2.77 → 吞吐 ×2.74、延迟不劣化，
    #                    说明测到的是计划模型的限制而非系统容量（docs/PERF.md §3.2）。
    #                    故此处**只报数不判定** —— 判了只会得到一个恒假的 FAIL。
    #   · throughput  —— **恒定负载**模型（无限循环 + 固定时长），
    #                    稳态吞吐 = 并发数 / 平均响应时间，这才是吞吐门槛唯一成立的场景。
    #   · soak        —— 恒定负载模型的低并发长跑（1h）：目标探泄漏/连接耗尽而非容量，
    #                    吞吐只报数；P95 / 错误率仍按统一门槛判定（长跑不劣化才是 PASS）。
    if plan == "smoke":
        print(f"  [SKIP] {'整体吞吐':<30} {overall_tps:>8.1f} req/s "
              f"（smoke 为正确性计划，不做吞吐判定）")
    elif plan == "throughput":
        if overall_tps >= MIN_THROUGHPUT:
            print(f"  [PASS] {'整体吞吐':<30} {overall_tps:>8.1f} req/s (>= {MIN_THROUGHPUT})")
        else:
            print(f"  [FAIL] {'整体吞吐':<30} {overall_tps:>8.1f} req/s (>= {MIN_THROUGHPUT})")
            fails.append("整体吞吐")
    else:  # load / soak：观测吞吐不作容量结论
        why = ("load 为固定工作量模型，观测吞吐受 ramp 时长限制" if plan == "load"
               else "soak 为低并发长稳，吞吐天然低于容量水位")
        print(f"  [INFO] {'整体吞吐':<30} {overall_tps:>8.1f} req/s "
              f"（{why}；容量标定请用 plan=throughput）")

    print()
    if fails:
        print(f"结论：未达标 —— {len(fails)} 项：{', '.join(fails)}")
        return 1
    print("结论：全部达标")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--plan", default="load",
                    choices=["load", "smoke", "throughput", "soak"],
                    help="load=混合负载（固定工作量）/ smoke=正确性冒烟 / "
                         "throughput=恒定负载（容量标定，唯一做吞吐判定者）/ "
                         "soak=低并发长稳 1h（探泄漏/连接耗尽，G5）")
    ap.add_argument("-J", dest="extra_j", action="append", default=[],
                    help="透传给 JMeter 的参数，形如 -J tgSubmit.threads=40")
    ap.add_argument("--max-error-rate", type=float, default=None,
                    help="覆盖所有 sampler 的允许错误率")
    ap.add_argument("--no-html", action="store_true",
                    help="不生成 HTML 报告（跳过报告目录的批量删除；门槛判定仍照常）")
    args = ap.parse_args()

    if args.max_error_rate is not None:
        for i in range(len(THRESHOLDS)):
            THRESHOLDS[i] = (THRESHOLDS[i][0], THRESHOLDS[i][1], args.max_error_rate)

    RESULT_DIR.mkdir(parents=True, exist_ok=True)
    jmeter = find_jmeter()
    print(f"[环境] JMeter = {jmeter}")
    ensure_user_pool()

    jtl = RESULT_DIR / f"{args.plan}.jtl"
    report_dir = RESULT_DIR / f"{args.plan}-report"
    wall = run_jmeter(jmeter, args.plan, args.extra_j, report_dir, jtl, html=not args.no_html)

    per = parse_jtl(jtl)
    rc = report(per, wall, args.plan)
    if args.no_html:
        print(f"\n原始数据：{jtl}（--no-html，未生成 HTML 报告）")
    else:
        print(f"\nHTML 报告：{report_dir / 'index.html'}")
    return rc


if __name__ == "__main__":
    sys.exit(main())
