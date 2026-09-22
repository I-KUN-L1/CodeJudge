#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
P6 端到端验收：前端产物 + 可观测性 + 监控栈 + 压测资产 + 文档。

设计原则（与前 5 阶段验收脚本一致）：
  · **只认实测**。每一条断言都必须真实发起请求 / 读真实文件 / 查真实容器；
  · 不达标就是 FAIL，不做"看起来应该没问题"的推断；
  · 需要外部依赖（Grafana/Prometheus）的分段如果服务未起，脚本会**自己起**（幂等）。

用法：
    python scripts/verify-p6.py                # 全量
    python scripts/verify-p6.py --no-monitor   # 跳过监控栈（不拉镜像/不起容器）
    python scripts/verify-p6.py --no-judge     # 跳过真实判题（省时间，约 30s）

前置：
    后端 8 个服务已启动（python scripts/dev-start-backend.py --wait）
    前端已构建（cd judge-web && npm run build）

环境变量：
    CJ_P6_GATEWAY       网关地址，默认 http://127.0.0.1:9080
    CJ_P3_PHONE         学员手机号，默认 13900000001
    CJ_P3_PASS          学员密码，默认 123456
    CJ_P6_GRAFANA_PASS  Grafana 密码，默认 codejudge
"""
import argparse
import json
import os
import pathlib
import re
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

try:
    import requests
except ImportError:
    print("需要 requests：pip install requests", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
GATEWAY = os.environ.get("CJ_P6_GATEWAY", "http://127.0.0.1:9080")
PHONE = os.environ.get("CJ_P3_PHONE", "13900000001")
PASSWORD = os.environ.get("CJ_P3_PASS", "123456")
GRAFANA_PASS = os.environ.get("CJ_P6_GRAFANA_PASS", "codejudge")
TIMEOUT = 15

SERVICES = {
    9080: "judge-gateway", 9081: "judge-auth", 9082: "judge-user",
    9083: "judge-problem", 9084: "judge-submission", 9085: "judge-worker",
    9086: "judge-contest", 9087: "judge-ai",
}

PASS, FAIL = 0, 0
SESSION = requests.Session()
SESSION.trust_env = False          # 忽略环境代理，否则 localhost 会被代理拦成 502


def check(ok, label, detail=""):
    global PASS, FAIL
    if ok:
        PASS += 1
    else:
        FAIL += 1
    print(f"  [{'PASS' if ok else 'FAIL'}] {label}" + (f"  — {detail}" if detail else ""))
    return ok


def section(title):
    print(f"\n{'=' * 78}\n{title}\n{'=' * 78}")


def get(url, **kw):
    kw.setdefault("timeout", TIMEOUT)
    return SESSION.get(url, **kw)


def post(url, **kw):
    kw.setdefault("timeout", TIMEOUT)
    return SESSION.post(url, **kw)


# =====================================================================
# A. 构建产物
# =====================================================================
def sec_a():
    section("A. 构建产物")
    # 注意产物命名不同：8 个服务经 spring-boot repackage → <module>.jar；
    # judge-common / judge-api 是纯库，未 repackage → <module>-<version>.jar。
    # 只按 <module>.jar 找会把这两个误判为"未构建"。
    mods = ["judge-common", "judge-api", "judge-gateway", "judge-auth", "judge-user",
            "judge-problem", "judge-submission", "judge-worker", "judge-contest", "judge-ai"]
    missing = []
    for m in mods:
        d = ROOT / m / "target"
        if not (list(d.glob(f"{m}.jar")) or list(d.glob(f"{m}-*.jar"))):
            missing.append(m)
    check(not missing, "10 个模块 jar 均已构建", f"缺失={missing}" if missing else "")

    dist = ROOT / "judge-web" / "dist"
    check((dist / "index.html").exists(), "前端产物 dist/index.html 存在")
    assets = list((dist / "assets").glob("*.js")) if (dist / "assets").is_dir() else []
    check(len(assets) >= 3, f"前端 assets 含 {len(assets)} 个 js 分包（>=3）",
          "" if len(assets) >= 3 else "构建产物不完整，先执行 npm run build")

    # 沙箱镜像
    try:
        out = subprocess.run(["docker", "images", "--format", "{{.Repository}}"],
                             capture_output=True, text=True, timeout=30).stdout
    except Exception as e:
        out = ""
        check(False, "docker 可用", str(e))
    for img in ["codejudge/judge-java21", "codejudge/judge-python312",
                "codejudge/judge-gcc13", "codejudge/judge-go122"]:
        check(img in out, f"沙箱镜像存在 {img}")


# =====================================================================
# B. 服务健康 + Prometheus 端点
# =====================================================================
def sec_b():
    section("B. 服务健康与指标端点（P6-A）")
    for port, name in SERVICES.items():
        try:
            r = get(f"http://127.0.0.1:{port}/actuator/health")
            body = r.json() if r.status_code == 200 else {}
            check(r.status_code == 200 and body.get("status") == "UP",
                  f"{name}:{port} /actuator/health UP", f"HTTP {r.status_code}")
        except Exception as e:
            check(False, f"{name}:{port} /actuator/health", f"{type(e).__name__}: {e}")
    for port, name in SERVICES.items():
        try:
            r = get(f"http://127.0.0.1:{port}/actuator/prometheus")
            has_metric = "jvm_memory_used_bytes" in r.text
            check(r.status_code == 200 and has_metric,
                  f"{name}:{port} /actuator/prometheus 200 且含 JVM 指标",
                  f"HTTP {r.status_code}, 含JVM={has_metric}")
        except Exception as e:
            check(False, f"{name}:{port} /actuator/prometheus", f"{type(e).__name__}: {e}")


# =====================================================================
# C. 指标契约
# =====================================================================
def sec_c():
    section("C. 指标契约（application 标签 / 业务 gauge / 直方图）")
    try:
        txt = get("http://127.0.0.1:9080/actuator/prometheus").text
    except Exception as e:
        check(False, "抓取 judge-gateway 指标", str(e))
        return
    check('application="judge-gateway"' in txt, "指标带 application 标签且值=服务名")
    check("http_server_requests_seconds_bucket" in txt,
          "存在 http_server_requests_seconds_bucket（percentiles-histogram 生效）")
    check("jvm_gc_pause_seconds" in txt, "存在 GC 指标")
    # Hikari 指标要去**有数据库的服务**上找 —— 网关无 DB，天然没有该指标
    try:
        user_txt = get("http://127.0.0.1:9082/actuator/prometheus").text
        check("hikaricp_connections_active" in user_txt and "hikaricp_connections_pending" in user_txt,
              "存在 Hikari 连接池指标（active/pending，资源占用）")
    except Exception as e:
        check(False, "抓取 judge-user 的 Hikari 指标", str(e))

    try:
        sub = get("http://127.0.0.1:9084/actuator/prometheus").text
    except Exception as e:
        check(False, "抓取 judge-submission 指标", str(e))
        return
    for m in ["judge_queue_backlog", "judge_dead_tasks", "judge_workers_online"]:
        found = re.search(rf"^{m}\{{[^}}]*\}}\s+([\d.eE+-]+|NaN)", sub, re.M)
        check(found is not None, f"业务指标 {m} 已暴露",
              f"值={found.group(1)}" if found else "未找到")


# =====================================================================
# D. 队列对账（P6 修复的实证）
# =====================================================================
def queue_backlog():
    txt = get("http://127.0.0.1:9084/actuator/prometheus").text
    m = re.search(r"^judge_queue_backlog\{[^}]*\}\s+([\d.eE+-]+|NaN)", txt, re.M)
    if not m or m.group(1) == "NaN":
        return None
    return float(m.group(1))


def dead_tasks():
    txt = get("http://127.0.0.1:9084/actuator/prometheus").text
    m = re.search(r"^judge_dead_tasks\{[^}]*\}\s+([\d.eE+-]+|NaN)", txt, re.M)
    return None if not m or m.group(1) == "NaN" else float(m.group(1))


def sec_d():
    section("D. 队列对账：积压指标必须能回落到真实值（本轮发现并修复的缺陷）")
    print("  背景：转死信的提交此前不会被从 judge:judge:queue:zset 摘除，")
    print("        导致 judge_queue_backlog 只增不减，空闲时也报积压（实测残留 2 个）。")
    print("        修复后由 JudgeCompensationService#queueReconcileScan 每 60s 对账摘除。")
    print("  验证方式：等待对账扫描把历史僵尸摘干净，断言积压归零。")

    d = dead_tasks()
    check(d is not None, f"读到 judge_dead_tasks（历史死信={d}）")
    if d:
        print(f"  [NOTE] judge_dead_tasks={d:.0f} —— 这些是 2026-09-20 18:16 那批"
              f"MysqlDataTruncation 遗留（该缺陷已在 P3 修复），指标正确反映了真实数据。")

    deadline = time.time() + 150
    last = None
    while time.time() < deadline:
        try:
            last = queue_backlog()
        except Exception:
            last = None
        if last == 0:
            break
        time.sleep(10)
    check(last == 0, "队列对账后 judge_queue_backlog 归零（僵尸成员已摘除）",
          f"当前={last}")


# =====================================================================
# E. 网关端到端（含真实判题）
# =====================================================================
def login():
    r = post(f"{GATEWAY}/accounts/login", json={"cellPhone": PHONE, "password": PASSWORD})
    body = r.json()
    if body.get("code") != 200:
        return None, body
    return body["data"]["accessToken"], body


def sec_e(do_judge=True):
    section("E. 网关端到端")
    token, body = login()
    if not check(token is not None, f"登录 {PHONE}", json.dumps(body, ensure_ascii=False)[:160]):
        return
    H = {"Authorization": f"Bearer {token}"}

    # 分页参数名必须是 pageNo/pageSize
    r = get(f"{GATEWAY}/problems/page", params={"pageNo": 1, "pageSize": 3}, headers=H)
    b = r.json()
    ok = r.status_code == 200 and b.get("code") == 200
    check(ok, "GET /problems/page（pageNo/pageSize 口径）", f"HTTP {r.status_code}")
    lst = (b.get("data") or {}).get("list") or []
    check(len(lst) >= 1, f"题目列表返回 {len(lst)} 条（<=3）", "")
    pid = lst[0]["id"] if lst else 4001

    r = get(f"{GATEWAY}/problems/{pid}", headers=H)
    check(r.json().get("code") == 200, f"GET /problems/{pid} 详情")

    r = get(f"{GATEWAY}/contests/page", params={"pageNo": 1, "pageSize": 5}, headers=H)
    cb = r.json()
    check(cb.get("code") == 200, "GET /contests/page")
    clist = (cb.get("data") or {}).get("list") or []
    if clist:
        cid = clist[0]["id"]
        r = get(f"{GATEWAY}/contests/{cid}/rank", headers=H)
        check(r.json().get("code") == 200, f"GET /contests/{cid}/rank 榜单")

    r = get(f"{GATEWAY}/submissions/page", params={"pageNo": 1, "pageSize": 5}, headers=H)
    check(r.json().get("code") == 200, "GET /submissions/page 提交记录")

    if not do_judge:
        print("  [SKIP] 真实判题（--no-judge）")
        return

    # ---- 真实提交 + 等判题终态 ----
    # ⚠ 必须固定用 A+B 题目（默认 4001）：列表首条不保证是 A+B，
    #   把 A+B 的解法提交到别的题目上会稳定得到 WA，从而把「判题链路正常」误判成失败。
    ab_pid = int(os.environ.get("CJ_P6_AB_PROBLEM", "4001"))
    # ⚠ 代码里必须带唯一标记：不带的话第二次执行本脚本会命中幂等（同一用户+题目+代码），
    #   下面的 idempotent=false 断言会失败 —— 验收脚本必须可重复执行。
    nonce = uuid.uuid4().hex
    code = ("import sys\n"
            f"# verify-p6 nonce={nonce}\n"
            "data = sys.stdin.read().split()\n"
            "print(sum(int(x) for x in data))\n")
    r = post(f"{GATEWAY}/submissions", headers=H,
             json={"problemId": ab_pid, "language": "PYTHON", "code": code})
    sb = r.json()
    if not check(sb.get("code") == 200, "POST /submissions 提交成功",
                 json.dumps(sb, ensure_ascii=False)[:200]):
        return
    sid = sb["data"]["id"]
    check(sb["data"].get("idempotent") is False, "首次提交未被幂等去重（idempotent=false）",
          f"idempotent={sb['data'].get('idempotent')}")

    deadline = time.time() + 120
    verdict, status = None, None
    while time.time() < deadline:
        r = get(f"{GATEWAY}/submissions/{sid}", headers=H)
        d = (r.json().get("data") or {})
        status, verdict = d.get("status"), d.get("verdict")
        if status in ("SUCCESS", "FAILED"):
            break
        time.sleep(3)
    check(status == "SUCCESS", f"判题进入终态 status={status}（判题机+沙箱链路通）")
    check(verdict == "AC", f"判题结论 verdict={verdict}（A+B 正解应为 AC）")
    check(queue_backlog() in (0.0, 1.0), f"提交已被消费出队（积压={queue_backlog()}）")

    # 同一份代码再提交一次 → 应命中幂等（压测必须让代码唯一才压得到判题链路）
    r = post(f"{GATEWAY}/submissions", headers=H,
             json={"problemId": ab_pid, "language": "PYTHON", "code": code})
    check(r.json()["data"].get("idempotent") is True,
          "重复提交命中幂等返回（idempotent=true）—— 说明压测必须让代码唯一")
    # 幂等命中时必须返回**同一条**提交，否则「幂等」只是返回了新建记录
    check(r.json()["data"].get("id") == sid, "幂等返回的是同一条提交记录")


# =====================================================================
# F. 监控栈
# =====================================================================
def sec_f():
    section("F. Prometheus / Alertmanager / Grafana（真实起容器）")
    mon = ROOT / "deploy" / "monitoring"
    try:
        p = subprocess.run(["docker", "compose", "-f", "docker-compose.monitoring.yml", "up", "-d"],
                           cwd=str(mon), capture_output=True, text=True, timeout=420)
        check(p.returncode == 0, "docker compose up -d（监控栈）",
              (p.stderr or p.stdout)[-200:] if p.returncode else "")
    except Exception as e:
        check(False, "启动监控栈", str(e))
        return

    # 等就绪
    for url, name in [("http://127.0.0.1:9090/-/ready", "Prometheus"),
                      ("http://127.0.0.1:9093/-/ready", "Alertmanager")]:
        ok = False
        for _ in range(20):
            try:
                if get(url, timeout=5).status_code == 200:
                    ok = True
                    break
            except Exception:
                pass
            time.sleep(3)
        check(ok, f"{name} 就绪（/-/ready）")

    # Prometheus targets —— 8 个服务必须全部 up
    # ⚠ 必须轮询：/-/ready 只表示 HTTP 服务已起，抓取间隔 15s，首个 scrape 完成前
    #   /api/v1/targets 会返回空列表（实测踩到过，会把配置正确误报成"抓取到 0 个 target"）。
    cj, down = [], []
    try:
        for _ in range(20):
            tg = get("http://127.0.0.1:9090/api/v1/targets").json()["data"]["activeTargets"]
            cj = [t for t in tg if t["labels"].get("job") == "codejudge"]
            if len(cj) == 8 and all(t["health"] == "up" for t in cj):
                break
            time.sleep(3)
        down = [t["labels"].get("application") for t in cj if t["health"] != "up"]
        check(len(cj) == 8, "Prometheus 抓取到 8 个 judge-* target", f"实际={len(cj)}")
        check(not down, "全部 target health=up（host.docker.internal 连通）", f"down={down}")
    except Exception as e:
        check(False, "读取 Prometheus targets", str(e))

    # 规则
    try:
        groups = get("http://127.0.0.1:9090/api/v1/rules").json()["data"]["groups"]
        n = sum(len(g["rules"]) for g in groups)
        check(n >= 11, f"告警规则已加载 {n} 条（>=11）", f"groups={len(groups)}")
    except Exception as e:
        check(False, "读取告警规则", str(e))

    # 抓取到的业务指标（端到端：应用 → Prometheus TSDB）
    try:
        res = []
        for _ in range(20):
            res = get("http://127.0.0.1:9090/api/v1/query",
                      params={"query": 'judge_workers_online{job="codejudge"}'}).json()["data"]["result"]
            if res:
                break
            time.sleep(3)
        check(len(res) == 1, "Prometheus 中可查到 judge_workers_online（业务指标已入库）",
              f"series={len(res)}")

        q = get("http://127.0.0.1:9090/api/v1/query",
                params={"query": 'up{job="codejudge"} == 1'}).json()["data"]["result"]
        check(len(q) == 8, "Prometheus 查询 up==1 返回 8 条", f"实际={len(q)}")
    except Exception as e:
        check(False, "Prometheus 查询业务指标", str(e))

    # Grafana
    ok = False
    for _ in range(20):
        try:
            if get("http://127.0.0.1:3001/api/health", timeout=5).json().get("database") == "ok":
                ok = True
                break
        except Exception:
            pass
        time.sleep(3)
    check(ok, "Grafana 就绪（/api/health database=ok）")
    if not ok:
        return

    # Grafana 鉴权走 HTTP Basic。
    # ⚠ 不要用 POST /api/login：该端点在 Grafana 11.3 上返回 404 {"message":"Not found"}，
    #   会让"凭据正确"被误判为失败。Basic Auth 是稳定可用的替代。
    gs = requests.Session()
    gs.trust_env = False
    gs.auth = ("admin", GRAFANA_PASS)
    try:
        ur = gs.get("http://127.0.0.1:3001/api/user", timeout=10)
        info = ur.json() if ur.status_code == 200 else {}
        check(ur.status_code == 200 and info.get("login") == "admin",
              "Grafana 管理员凭据有效（Basic Auth /api/user）",
              f"HTTP {ur.status_code}")
    except Exception as e:
        check(False, "Grafana 管理员鉴权", str(e))

    try:
        ds = gs.get("http://127.0.0.1:3001/api/datasources", timeout=10).json()
        names = [d.get("name") for d in ds]
        check("Prometheus" in names, f"Grafana 数据源已自动装配 {names}")
    except Exception as e:
        check(False, "读取 Grafana 数据源", str(e))

    try:
        s = gs.get("http://127.0.0.1:3001/api/search", params={"query": "CodeJudge"}, timeout=10).json()
        titles = [d.get("title") for d in s]
        check(len(s) >= 4, f"Grafana 看板已装载 {len(s)} 个（>=4）", f"{titles}")
        for want in ["CodeJudge 服务总览", "CodeJudge HTTP 性能",
                     "CodeJudge 判题链路", "CodeJudge AI 点评"]:
            check(want in titles, f"看板存在：{want}")
    except Exception as e:
        check(False, "读取 Grafana 看板", str(e))


# =====================================================================
# G. 压测资产
# =====================================================================
def sec_g():
    section("G. JMeter 压测资产")
    for name, want_tgs in [("smoke.jmx", 1), ("load.jmx", 6), ("throughput.jmx", 2)]:
        p = ROOT / "perf-test" / "jmx" / name
        if not check(p.exists(), f"{name} 存在"):
            continue
        try:
            root = ET.parse(str(p)).getroot()
            tgs = list(root.iter("ThreadGroup"))
            samplers = [e.get("testname") for e in root.iter("HTTPSamplerProxy")]
            check(len(tgs) == want_tgs, f"{name} 线程组 {len(tgs)} 个（期望 {want_tgs}）")
            check(len(samplers) >= 5, f"{name} 覆盖 {len(samplers)} 个请求")
            # 关键断言：业务码断言必须存在（只看 HTTP 200 会漏掉业务失败）
            body = p.read_text(encoding="utf-8")
            check("&quot;code&quot;:200" in body, f"{name} 含业务码断言（HTTP 200 != 业务成功）")

            # 恒定负载计划的两条**防跑飞**断言（2026-09-21 实际踩过一次）：
            #   scheduler 若写成 ${__P(...)}，JMeter 的 <boolProp> 不做函数替换，
            #   整串被判成 false → 「loops=-1 无限循环 + 调度器关闭」= 永不停止。
            #   那次配 180s 跑成 13 分钟 / 226 万样本 / .jtl 400MB，靠手工 kill 才停。
            if name == "throughput.jmx":
                sched = [e.text for e in root.iter("boolProp")
                         if e.get("name") == "ThreadGroup.scheduler"]
                check(len(sched) == want_tgs and all((t or "").strip() == "true" for t in sched),
                      f"{name} 调度器为**字面量** true（防无限运行）",
                      f"实际={sched}")
                durs = [e.text for e in root.iter("stringProp")
                        if e.get("name") == "ThreadGroup.duration"]
                check(len(durs) == want_tgs and all("__P(" in (t or "") for t in durs),
                      f"{name} 各线程组均配 duration 且可 -J 覆盖", f"实际={durs}")
                loops = [e.text for e in root.iter("stringProp")
                         if e.get("name") == "LoopController.loops"]
                check(len(loops) == want_tgs and all((t or "").strip() == "-1" for t in loops),
                      f"{name} 为无限循环（恒定负载模型）", f"实际={loops}")
        except Exception as e:
            check(False, f"{name} 解析", str(e))

    for f in ["seed-users.py", "run-perf.py", "README.md", "RUNBOOK.md"]:
        check((ROOT / "perf-test" / f).exists(), f"perf-test/{f} 存在")

    # run-perf.py 的墙钟看门狗：没有它，上面那种计划错误会变成"机器被打满且不停止"。
    rp = (ROOT / "perf-test" / "run-perf.py").read_text(encoding="utf-8")
    check("CJ_PERF_MAX_WALL" in rp and "def watchdog_seconds" in rp,
          "run-perf.py 含 JMeter 墙钟看门狗（防无界运行）")
    check("CJ_PERF_HOST" in rp,
          "run-perf.py 支持 CJ_PERF_HOST（独立压测机指定被测网关）")

    # 账号池：真实播种（幂等，已存在会复用）。这是压测能否跑通的前置 —— 没有账号池，
    # 100 并发会因拿不到 token 全线 401，跑出来的只是一堆错误。
    csvp = ROOT / "perf-test" / "csv" / "users.csv"
    try:
        p = subprocess.run([sys.executable, str(ROOT / "perf-test" / "seed-users.py"),
                            "--count", "30"],
                           capture_output=True, text=True, timeout=180)
        tail = (p.stdout or p.stderr).strip().splitlines()[-2:]
        check(p.returncode == 0 and csvp.exists(), "压测账号池已生成（seed-users.py）",
              " | ".join(tail))
    except Exception as e:
        check(False, "播种压测账号池", str(e))

    # JMeter 可执行
    jm = pathlib.Path(os.environ.get("JMETER_HOME", r"D:\1\jmeter")) / "bin" / "jmeter.bat"
    check(jm.exists(), f"JMeter 可执行文件存在（{jm}）")


# =====================================================================
# H. 文档
# =====================================================================
def sec_h():
    section("H. 文档完整性")
    docs = {
        "README.md": ["快速开始", "架构"],
        "docs/ARCHITECTURE.md": ["模块职责", "关键链路", "端口规划"],
        "docs/API-REFERENCE.md": ["统一响应体", "分页", "SSE 事件契约"],
        "docs/DEPLOYMENT.md": ["依赖清单", "启动流程", "本机环境陷阱"],
    }
    for rel, keys in docs.items():
        p = ROOT / rel
        if not check(p.exists(), f"{rel} 存在"):
            continue
        txt = p.read_text(encoding="utf-8", errors="replace")
        miss = [k for k in keys if k not in txt]
        check(not miss, f"{rel} 含关键小节", f"缺={miss}" if miss else "")

    for f in ["judge-web/README.md", "perf-test/README.md",
              "deploy/monitoring/prometheus/prometheus.yml",
              "deploy/monitoring/prometheus/rules/codejudge-alerts.yml",
              "deploy/monitoring/docker-compose.monitoring.yml"]:
        check((ROOT / f).exists(), f"{f} 存在")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-monitor", action="store_true", help="跳过监控栈（不起容器）")
    ap.add_argument("--no-judge", action="store_true", help="跳过真实判题")
    args = ap.parse_args()

    print(f"P6 端到端验收  网关={GATEWAY}  学员={PHONE}")
    t0 = time.time()

    sec_a()
    sec_b()
    sec_c()
    sec_d()
    sec_e(do_judge=not args.no_judge)
    if not args.no_monitor:
        sec_f()
    sec_g()
    sec_h()

    print(f"\n{'=' * 78}")
    print(f"结果：PASS={PASS}  FAIL={FAIL}   耗时 {time.time() - t0:.1f}s")
    print("=" * 78)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
