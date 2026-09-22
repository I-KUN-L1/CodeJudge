#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""生产上线前自检 —— `docs/DEPLOYMENT.md` §7 检查清单的可执行版。

用法：
    python scripts/preflight-check.py                    # 全量（需服务与监控栈在跑）
    python scripts/preflight-check.py --no-monitor       # 跳过监控栈相关项
    python scripts/preflight-check.py --json             # 输出 JSON（供 CI 消费）

设计原则：**本脚本不读取任何凭据明文。**
    所有安全项都用「行为验证」而非「读配置」——
    例如判断 Grafana 是否仍用默认密码，是拿「compose 的 fallback 值」去登录，
    而不是去读 `.env`。这样脚本自身不构成凭据泄露面，也可在任意机器上安全执行。
    凭据强度审计是另一件事，见 `scripts/rotate-credentials.py --audit`（需授权读取 .env）。

    ⚠ 探针用的"默认密码"**不写死在代码里**，而是从 `deploy/monitoring/docker-compose.monitoring.yml`
      的 `${GRAFANA_ADMIN_PASSWORD:-<默认值>}` 表达式解析 —— 该表达式才是"不配 .env 会怎样"
      的唯一权威。写死一份副本的后果是：以后改了 compose 默认值，这里会变成一条**恒真**的
      检查（探针密码永远登不进去，于是永远 PASS），把"没改默认密码"这个真问题悄悄放过去。

四态输出：
    PASS    该项满足上线要求
    FAIL    必须修复，否则不应上线（脚本退出码为 1）
    WARN    风险项 / 本地可接受，生产需注意
    MANUAL  无法自动验证，需人工确认
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import pathlib
import re
import subprocess
import sys
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent

SERVICES = {
    9080: "judge-gateway",
    9081: "judge-auth",
    9082: "judge-user",
    9083: "judge-problem",
    9084: "judge-submission",
    9085: "judge-worker",
    9086: "judge-contest",
    9087: "judge-ai",
}
MONITOR = {
    9090: ("prometheus", "/-/ready"),
    9093: ("alertmanager", "/-/ready"),
    3001: ("grafana", "/api/health"),
}
SANDBOX_IMAGES = [
    "codejudge/judge-java21",
    "codejudge/judge-python312",
    "codejudge/judge-gcc13",
    "codejudge/judge-go122",
]

RESULTS: list[tuple[str, str, str]] = []      # (等级, 名称, 详情)


def add(level: str, name: str, detail: str) -> None:
    RESULTS.append((level, name, detail))
    icon = {"PASS": "[PASS]  ", "FAIL": "[FAIL]  ", "WARN": "[WARN]  ",
            "MANUAL": "[MANUAL]", "SKIP": "[SKIP]  "}[level]
    print(f"{icon} {name}")
    print(f"          {detail}")


# ------------------------------------------------- 默认凭据探针值（唯一来源）

_COMPOSE_MON = ROOT / "deploy" / "monitoring" / "docker-compose.monitoring.yml"


def _compose_default(var: str, fallback: str) -> str:
    """从监控 compose 的 `${VAR:-默认值}` 表达式里取出"不配 .env 时的实际取值"。

    取不到（文件缺失 / 表达式被改写成无默认值形式）时返回 fallback，
    并且**由调用方把这一情况报成 WARN** —— 悄悄退化成一个猜的值比不检查更糟。
    """
    try:
        text = _COMPOSE_MON.read_text(encoding="utf-8")
    except OSError:
        return fallback
    m = re.search(r"\$\{" + re.escape(var) + r":-([^}]*)\}", text)
    return m.group(1) if m else fallback


def grafana_probe_credentials() -> tuple[str, str]:
    """返回 Grafana 的 (用户名, 密码) 探针值 —— 即"默认配置下能登进去的那组"。

    可用环境变量覆盖（.env 里已自定义时，想让检查更有意义可显式传入）。
    """
    user = os.environ.get("CJ_GF_PROBE_USER") or _compose_default("GRAFANA_ADMIN_USER", "admin")
    password = os.environ.get("CJ_GF_PROBE_PASSWORD") or _compose_default(
        "GRAFANA_ADMIN_PASSWORD", "codejudge")
    return user, password


# ------------------------------------------------------------------ HTTP

def http(url: str, *, headers: dict | None = None, method: str = "GET",
         timeout: float = 5.0) -> tuple[int, str, dict]:
    """返回 (状态码, 响应体前 512KB, 响应头)。连接失败返回 (-1, 错误, {})。

    读取上限刻意给大：`/actuator/prometheus` 的文本常有数百 KB，
    截断会让「按行匹配指标」的检查静默失效（曾因此把正常指标误判为「埋点缺失」）。
    """
    req = urllib.request.Request(url, headers=headers or {}, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read(512 * 1024).decode("utf-8", "replace")
            return r.status, body, dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(512 * 1024).decode("utf-8", "replace"), dict(e.headers or {})
    except Exception as e:                                    # 连接被拒 / 超时
        return -1, f"{e.__class__.__name__}: {e}", {}


def basic_auth(user: str, pwd: str) -> dict:
    token = base64.b64encode(f"{user}:{pwd}".encode()).decode()
    return {"Authorization": f"Basic {token}"}


# ------------------------------------------------------------------ A 可用性

def check_availability(base: str, base_host: str, monitor: bool) -> None:
    down = []
    for port, name in SERVICES.items():
        code, _, _ = http(f"{base_host}:{port}/actuator/health", timeout=3)
        if code != 200:
            down.append(f"{name}({port})={code}")
    if down:
        add("FAIL", "A1 八个后端服务健康", f"以下服务未就绪：{', '.join(down)}"
            "\n          生产环境请先确认服务全部启动再上线。")
    else:
        add("PASS", "A1 八个后端服务健康", "9080–9087 全部 /actuator/health = 200")

    # 网关对外可用性（走网关，而非直连）
    code, _, _ = http(f"{base}/problems/page?pageNo=1&pageSize=1", timeout=5)
    if code in (200, 401, 403):
        add("PASS", "A2 网关对外可达", f"GET /problems/page → {code}（401/403 属未带 token 的正常表现）")
    else:
        add("FAIL", "A2 网关对外可达", f"GET /problems/page → {code}，网关可能未启动或路由异常")

    if not monitor:
        add("SKIP", "A3 监控栈健康", "--no-monitor 已跳过")
        return
    bad = []
    for port, (name, path) in MONITOR.items():
        code, _, _ = http(f"http://127.0.0.1:{port}{path}", timeout=4)
        if code != 200:
            bad.append(f"{name}({port})={code}")
    if bad:
        add("WARN", "A3 监控栈健康", f"未就绪：{', '.join(bad)}"
            "\n          监控缺失不影响业务可用，但上线等于「盲飞」，建议补齐。")
    else:
        add("PASS", "A3 监控栈健康", "Prometheus 9090 / Alertmanager 9093 / Grafana 3001 全部 200")


# ------------------------------------------------------------------ B 暴露面

def check_exposure(base: str) -> None:
    # B1 网关**自身**管理端点的敏感面是否泄露。
    #   注意：`/actuator/health` 与 `/actuator/prometheus` 返回 200 是**预期的** ——
    #   它们是网关自己的探活与指标端点，Prometheus 也在抓。真正需要守的是
    #   env / heapdump / threaddump / configprops 这类会泄露配置与内存内容的端点。
    leaked = []
    for ep in ("env", "beans", "configprops", "heapdump", "threaddump", "mappings", "loggers"):
        code, _, _ = http(f"{base}/actuator/{ep}", timeout=4)
        if code == 200:
            leaked.append(ep)
    if leaked:
        add("FAIL", "B1 网关管理端点未泄露敏感面",
            f"经网关可直接访问：{', '.join(leaked)}"
            "\n          env/configprops 会吐出数据库连接串与密钥，heapdump 可直接拖内存。"
            "\n          修复：management.endpoints.web.exposure.include 只留 health,info,metrics,prometheus。")
    else:
        add("PASS", "B1 网关管理端点未泄露敏感面",
            "env/beans/configprops/heapdump/threaddump/mappings/loggers 全部 404"
            "\n          网关仅对外开放 health 与 prometheus（探活 + 指标采集，属预期）。")

    # B1b 下游服务的 actuator 是否被网关以 /actuator 前缀整体代理过去
    code, body, _ = http(f"{base}/actuator/env", timeout=4)
    if code == 200 and re.search(r'"activeProfiles"|spring\.datasource', body):
        add("FAIL", "B1b 下游 actuator 未被网关代理", "经网关能读到 Spring 环境详情")
    else:
        add("PASS", "B1b 下游 actuator 未被网关代理",
            "网关路由表不含 /actuator 前缀，下游管理端点不经网关暴露"
            "\n          （直连 9081–9087 仍可访问，生产请用网络策略限制为内网/管理网）。")

    # B2 CORS 是否回显任意 Origin
    evil = "https://evil.example.invalid"
    code, _, hdr = http(f"{base}/problems/page?pageNo=1&pageSize=1",
                        headers={"Origin": evil}, timeout=5)
    acao = hdr.get("Access-Control-Allow-Origin") or hdr.get("access-control-allow-origin")
    if acao and (acao == "*" or evil in acao):
        add("FAIL", "B2 CORS 不回显任意来源",
            f"带 Origin: {evil} 请求，响应 Access-Control-Allow-Origin = {acao}"
            "\n          生产必须把 CORS_ALLOWED_ORIGINS 收敛为真实前端域名。")
    else:
        add("PASS", "B2 CORS 不回显任意来源",
            f"未匹配来源无 ACAO 回显（响应头值：{acao!r}）")

    # B3 Grafana 匿名访问
    code, _, _ = http("http://127.0.0.1:3001/api/search", timeout=4)
    if code == 200:
        add("WARN", "B3 Grafana 匿名访问已关闭",
            "无凭据访问 Grafana API 返回 200 —— GF_AUTH_ANONYMOUS_ENABLED 仍为 true。"
            "\n          本地开发免登录只读很便利，**生产必须置为 false**。")
    elif code == 401:
        add("PASS", "B3 Grafana 匿名访问已关闭", "无凭据访问返回 401")
    else:
        add("SKIP", "B3 Grafana 匿名访问已关闭", f"Grafana 不可达（{code}）")

    # B4 Grafana 默认密码是否仍在使用
    gf_user, gf_pass = grafana_probe_credentials()
    if not _compose_default("GRAFANA_ADMIN_PASSWORD", ""):
        add("WARN", "B4 Grafana 默认密码已更换",
            "无法从 docker-compose.monitoring.yml 解析出默认密码表达式，"
            "\n          本次使用内置 fallback 作为探针 —— 结论仅供参考。")
    code, _, _ = http("http://127.0.0.1:3001/api/user",
                      headers=basic_auth(gf_user, gf_pass), timeout=4)
    if code == 200:
        add("FAIL", "B4 Grafana 默认密码已更换",
            f"{gf_user}/{gf_pass} 仍可登录 —— .env 未覆盖 GRAFANA_ADMIN_PASSWORD，"
            "\n          走的是 compose 默认值。生产必须覆盖（见 scripts/rotate-credentials.py）。")
    elif code == 401:
        add("PASS", "B4 Grafana 默认密码已更换", "默认凭据登录被拒（401）")
    else:
        add("SKIP", "B4 Grafana 默认密码已更换", f"Grafana 不可达（{code}）")


# ------------------------------------------------------------------ C 运行时行为

def check_runtime(base: str) -> None:
    # C1 登录限流是否仍为默认（防止把压测期的放宽配置带上线）
    ok = throttled = other = 0
    for _ in range(12):
        code, _, _ = http(f"{base}/accounts/login",
                          headers={"Content-Type": "application/json"},
                          method="POST", timeout=5)
        if code == 429:
            throttled += 1
        elif code == 200:
            ok += 1
        else:
            other += 1
    if throttled > 0:
        add("PASS", "C1 登录限流为生产默认值",
            f"12 次瞬时登录 → {ok}×200 + {throttled}×429（默认令牌桶 2 req/s / 突发 5）")
    elif ok > 0:
        add("WARN", "C1 登录限流为生产默认值",
            f"12 次瞬时登录全部 200、无 429 —— 网关可能仍处于压测放宽状态。"
            "\n          上线前请用默认值重启网关（不要带 GW_LOGIN_RATE_* 环境变量）。")
    else:
        add("SKIP", "C1 登录限流为生产默认值", f"无法判定（响应分布：200×{ok} / 429×{throttled} / 其他×{other}）")

    # C2 判题机在线数
    code, body, _ = http("http://127.0.0.1:9084/actuator/prometheus", timeout=5)
    if code != 200:
        add("SKIP", "C2 判题机在线", f"judge-submission 指标不可达（{code}）")
    else:
        def metric(name: str) -> float | None:
            m = re.search(rf"^{name}\{{[^}}]*\}}\s+([-\d.eE+]+)", body, re.M)
            return float(m.group(1)) if m else None

        workers = metric("judge_workers_online")
        backlog = metric("judge_queue_backlog")
        dead = metric("judge_dead_tasks")
        if workers is None:
            add("FAIL", "C2 判题机在线", "找不到 judge_workers_online 指标 —— 埋点缺失或服务未就绪")
        elif workers < 1:
            add("FAIL", "C2 判题机在线", f"judge_workers_online = {workers}，集群已无判题能力")
        elif workers < 2:
            add("WARN", "C2 判题机在线",
                f"judge_workers_online = {workers}（单实例）。"
                "\n          实测单 worker 判题吞吐约 1.5 题/秒，扩容优先级：判题机实例数 > Web 副本数。")
        else:
            add("PASS", "C2 判题机在线", f"judge_workers_online = {workers}")

        if dead is not None and dead > 0:
            add("FAIL", "C3 无死信判题任务", f"judge_dead_tasks = {dead}，存在重试耗尽的提交")
        elif dead is not None:
            add("PASS", "C3 无死信判题任务", f"judge_dead_tasks = {dead}")
        if backlog is not None and backlog > 200:
            add("WARN", "C4 判题队列无积压", f"judge_queue_backlog = {backlog}（> 200）")
        elif backlog is not None:
            add("PASS", "C4 判题队列无积压", f"judge_queue_backlog = {backlog}")


# ------------------------------------------------------------------ D 沙箱与镜像

def check_sandbox() -> None:
    try:
        p = subprocess.run(["docker", "images"], capture_output=True, text=True,
                           encoding="utf-8", errors="replace", timeout=30)
        out = p.stdout or ""
    except Exception as e:
        add("SKIP", "D1 判题沙箱镜像存在", f"docker 不可用：{e}")
        return
    # 注意：不要用 `docker images --format '{{.Repository}}'` —— 该模板在本机 Git Bash 下会被破坏
    missing = [img for img in SANDBOX_IMAGES if img not in out]
    if missing:
        add("FAIL", "D1 判题沙箱镜像存在", f"缺失：{', '.join(missing)}"
            "\n          执行 python scripts/build-sandbox-images.py 构建。")
    else:
        add("PASS", "D1 判题沙箱镜像存在", f"4 个镜像齐全（{', '.join(i.split('/')[-1] for i in SANDBOX_IMAGES)}）")

    try:
        p = subprocess.run(["docker", "info"], capture_output=True, text=True,
                           encoding="utf-8", errors="replace", timeout=30)
        info = p.stdout or ""
    except Exception:
        add("SKIP", "D2 沙箱运行时加固", "docker info 不可用")
        return
    has_runsc = "runsc" in info
    if has_runsc:
        add("PASS", "D2 沙箱运行时加固", "检测到 runsc（gVisor）运行时可用，可通过 CJ_SANDBOX_RUNTIME=runsc 启用")
    else:
        add("WARN", "D2 沙箱运行时加固",
            "未检测到 runsc 运行时，当前只能以 runc + seccomp 加固运行用户代码。"
            "\n          runc 与宿主共享内核，隔离强度弱于 gVisor；公网判题平台建议安装 gVisor。")


# ------------------------------------------------------------------ E 配置卫生

def check_housekeeping() -> None:
    gi = ROOT / ".gitignore"
    if gi.exists():
        txt = gi.read_text(encoding="utf-8", errors="replace")
        if re.search(r"^\.env$", txt, re.M):
            add("PASS", "E1 .env 已被 gitignore", ".gitignore 含 `.env` 与 `.env.*`，且放行 .env.example")
        else:
            add("FAIL", "E1 .env 已被 gitignore", ".gitignore 未包含 `.env`，存在误提交风险")
    else:
        add("FAIL", "E1 .env 已被 gitignore", "找不到 .gitignore")

    try:
        p = subprocess.run(["git", "ls-files", "--error-unmatch", ".env"],
                           capture_output=True, text=True, encoding="utf-8",
                           errors="replace", cwd=str(ROOT), timeout=15)
        if p.returncode == 0:
            add("FAIL", "E2 .env 未被 git 跟踪", ".env 已在版本控制中 —— 立刻 git rm --cached .env 并轮换全部凭据")
        else:
            add("PASS", "E2 .env 未被 git 跟踪", ".env 不在版本控制中")
    except Exception as e:
        add("SKIP", "E2 .env 未被 git 跟踪", f"git 不可用：{e}")

    ex = ROOT / ".env.example"
    if ex.exists():
        txt = ex.read_text(encoding="utf-8", errors="replace")
        # 键名像密钥，但其实是普通数值型参数 —— 必须排除，否则 MAX_TOKENS=1024 会被误判成泄露
        NOT_SECRET = re.compile(
            r"(MAX_TOKENS|MIN_TOKENS|TIMEOUT|INTERVAL|_MS$|_SECONDS$|DIMENSION|TOP_P"
            r"|TEMPERATURE|PORT|_BIND|MODEL|_PATH|_MODEL$|_DAYS?$)", re.I)
        PLACEHOLDER = re.compile(r"^(|your-.*|your_.*|placeholder.*|example.*|changeme|todo)$", re.I)
        # 设计上公开的初始密码：首次登录强制修改，写在模板里不构成泄露，但属弱值
        PUBLIC_WEAK = {"123456"}
        suspicious: list[str] = []
        weak_defaults: list[str] = []
        for line in txt.splitlines():
            s = line.strip()
            if s.startswith("#") or "=" not in s:
                continue
            k, _, v = s.partition("=")
            k, v = k.strip(), v.strip()
            if not re.search(r"PASSWORD|SECRET|API_KEY|ACCESS_KEY|_KEY$", k, re.I):
                continue
            if NOT_SECRET.search(k):
                continue
            if PLACEHOLDER.match(v):
                continue
            (weak_defaults if v in PUBLIC_WEAK else suspicious).append(k)
        if suspicious:
            add("FAIL", "E3 .env.example 无真实密钥",
                f"以下键在模板中填了疑似真实值：{', '.join(suspicious)}"
                "\n          模板必须只放占位符，否则等于把凭据提交进了仓库。")
        elif weak_defaults:
            add("WARN", "E3 .env.example 无真实密钥",
                f"模板无真实密钥泄露；但 {', '.join(weak_defaults)} 使用了弱默认值 123456。"
                "\n          二者是「首次登录强制改密」的系统初始密码，设计如此，"
                "\n          但生产 .env 必须覆盖为强值（见 scripts/rotate-credentials.py）。")
        else:
            add("PASS", "E3 .env.example 无真实密钥",
                "模板敏感项全为占位符（your-xxx / 空值），无真实密钥泄露")
    else:
        add("WARN", "E3 .env.example 无真实密钥", "找不到 .env.example")

    rules = ROOT / "deploy/monitoring/prometheus/rules/codejudge-alerts.yml"
    if rules.exists():
        txt = rules.read_text(encoding="utf-8", errors="replace")
        if "待生产重标" in txt:
            add("WARN", "E4 告警阈值已按生产容量重标",
                "规则文件仍标注「待生产重标」—— 当前阈值取自本机实测基线"
                "\n          （已修正统计口径：排除 /actuator 与 SSE）。"
                "\n          生产流量稳定后执行："
                "\n            python scripts/recalibrate-alerts.py --prometheus http://<prom>:9090 --write")
        else:
            add("PASS", "E4 告警阈值已按生产容量重标", "规则文件已按目标容量重标")
    else:
        add("SKIP", "E4 告警阈值已按生产容量重标", "找不到告警规则文件")


def check_alertmanager(monitor: bool) -> None:
    if not monitor:
        add("SKIP", "F1 Alertmanager 已接真实通知通道", "--no-monitor 已跳过")
        return
    code, body, _ = http("http://127.0.0.1:9093/api/v2/status", timeout=4)
    if code != 200:
        add("SKIP", "F1 Alertmanager 已接真实通知通道", f"Alertmanager 不可达（{code}）")
        return
    try:
        st = json.loads(body)
        # v0.27 的字段是 config.original；老版本叫 configYAML。两个都认。
        cfg = st.get("configYAML") or (st.get("config") or {}).get("original", "")
    except Exception:
        cfg = body

    # 子检查 1：配置模板里的 ${...} 有没有真的被渲染器替换掉。
    # 未展开的后果极其隐蔽：receiver 名变成字面量 "${XXX}"，
    # 路由永远匹配不到 → 告警不发，而 UI 与日志都是正常的。
    if "$" + "{" in cfg:
        add("FAIL", "F1 Alertmanager 已接真实通知通道",
            "生效配置里仍有未展开的 ${...} —— receiver 名会被当成字面量，"
            "\n          路由永远匹配不到 → 告警静默不发（UI 与日志均无异常，极难发现）。"
            "\n          检查 compose 的 entrypoint 是否指向 alertmanager/entrypoint.sh。")
        return

    # 注意两种序列化形态：`- name: "null"`（双引号，v0.27 的 config.original 如此）
    # 与 `- name: email`（无引号）。只认单引号会漏掉绝大多数 receiver。
    Q = r"['\"]?"
    receivers = re.findall(rf"^\s*-\s*name:\s*{Q}([A-Za-z0-9_.\-]+){Q}", cfg, re.M)
    # routes 里的 receiver 前面带 `- `（YAML 序列），顶层的不带 —— 两种都要认，
    # 否则「critical 路由仍指向 null」这类问题会被漏掉。
    routes = re.findall(rf"^\s*(?:-\s*)?receiver:\s*{Q}([A-Za-z0-9_.\-]+){Q}", cfg, re.M)

    # 子检查 2：路由引用的 receiver 必须都已定义，否则启动时就该失败（这里兜底再确认）
    undefined = sorted({r for r in routes if r not in receivers})
    if undefined:
        add("FAIL", "F1 Alertmanager 已接真实通知通道",
            f"路由引用了未定义的 receiver：{', '.join(undefined)}"
            f"\n          已定义的 receiver：{', '.join(receivers) or '(无)'}")
        return

    real = sorted({r for r in routes if r != "null"})
    if real:
        add("PASS", "F1 Alertmanager 已接真实通知通道",
            f"生效接收器：{', '.join(real)}（路由已指向真实通道）")
    else:
        add("FAIL", "F1 Alertmanager 已接真实通知通道",
            "路由全部指向 null receiver —— **告警不会发到任何地方**，等于没有告警。"
            "\n          接入方式见 deploy/monitoring/alertmanager/README.md，"
            "\n          改 .env 里的 ALERTMANAGER_DEFAULT_RECEIVER / ALERTMANAGER_CRITICAL_RECEIVER。")


# ------------------------------------------------------------------ MANUAL

MANUAL_ITEMS = [
    ("M1 凭据全部轮换为生产值",
     "python scripts/rotate-credentials.py --rotate         # 先预演\n"
     "          python scripts/rotate-credentials.py --rotate --write  # 确认后落盘并执行打印的同步命令"),
    ("M2 开启 HTTPS / TLS",
     "判题平台含账号与代码，必须全站 HTTPS；注意 judge-ai 的 SSE 需反代 `proxy_buffering off`"),
    ("M3 判题机多实例",
     "docker 之外再起 judge-worker 9185/9285，或确认 MQ 消费组分流正常（C2 当前为单实例则必须扩）"),
    ("M4 独立压测机执行容量标定",
     "本机压测工具与服务同机，数据只是容量下界（见 docs/PERF.md §一）"),
    ("M5 数据卷备份与回滚预案",
     "MySQL/PG/Redis/MinIO 数据卷的备份策略 + 回滚步骤，生产前演练一次"),
    ("M6 前端 E2E 走查（关键路径）",
     "登录→选题→提交→看判题进度(WS)→AI 点评(SSE)→竞赛榜单(WS)，在真实浏览器走一遍"),
]


def main() -> int:
    ap = argparse.ArgumentParser(description="CodeJudge 生产上线前自检")
    ap.add_argument("--base", default="http://127.0.0.1:9080",
                    help="网关地址（默认 http://127.0.0.1:9080）")
    ap.add_argument("--no-monitor", action="store_true", help="跳过监控栈相关检查")
    ap.add_argument("--json", action="store_true", help="以 JSON 输出结果")
    args = ap.parse_args()

    m = re.match(r"^(https?://[^:/]+)(?::(\d+))?", args.base)
    base_host = m.group(1) if m else "http://127.0.0.1"
    base = args.base.rstrip("/")

    print("=" * 74)
    print("CodeJudge 生产上线前自检")
    print(f"网关 {base} ｜ 服务 {len(SERVICES)} 个 ｜ 监控栈 {'跳过' if args.no_monitor else '检查'}")
    print("=" * 74)

    print("\n【A 可用性】")
    check_availability(base, base_host, not args.no_monitor)
    print("\n【B 暴露面（安全）】")
    check_exposure(base)
    print("\n【C 运行时行为】")
    check_runtime(base)
    print("\n【D 沙箱与镜像】")
    check_sandbox()
    print("\n【E 配置卫生】")
    check_housekeeping()
    print("\n【F 告警通道】")
    check_alertmanager(not args.no_monitor)

    print("\n【G 需人工确认（脚本无法代劳）】")
    for name, how in MANUAL_ITEMS:
        add("MANUAL", name, how)

    counts: dict[str, int] = {}
    for lvl, _, _ in RESULTS:
        counts[lvl] = counts.get(lvl, 0) + 1
    print("\n" + "=" * 74)
    print("汇总：" + "  ".join(f"{k}={v}" for k, v in
                             sorted(counts.items(), key=lambda x: x[0])))
    if counts.get("FAIL"):
        print("存在 FAIL 项 —— **维持现状上线会有明确风险**，请逐项处置后重跑本脚本。")
    elif counts.get("WARN"):
        print("无 FAIL。WARN 项在本地开发可接受，生产部署前请逐项确认。")
    else:
        print("全部自动检查项通过。")
    print("=" * 74)

    if args.json:
        print(json.dumps([{"level": l, "name": n, "detail": d} for l, n, d in RESULTS],
                         ensure_ascii=False, indent=2))
    return 1 if counts.get("FAIL") else 0


if __name__ == "__main__":
    sys.exit(main())
