#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CodeJudge 一键启动：基础设施 + 8 个后端服务（+ 可选 监控栈 / 前端）。

────────────────────────────────────────────────────────────────────────────────
为什么需要这个脚本
────────────────────────────────────────────────────────────────────────────────
在它之前，"把项目跑起来"是三步互不相干的手工动作，且顺序错了不会报错、只会
在别处表现出古怪症状：

    docker-compose up -d                      # 基础设施
    python scripts/dev-start-backend.py --wait  # 8 个服务
    (cd deploy/monitoring && docker-compose --env-file ../../.env -f ... up -d)
    (cd judge-web && npm run dev)             # 前端

其中最要命的是**服务顺序**。原来的 dev-start-backend.py 把 judge-auth 排在
judge-user 之前，而 judge-auth 的首个管理员引导（ApplicationRunner）一启动就要
经 Feign 调 judge-user 问"有没有管理员"——user 还没起来时这一步抛异常，
被 AdminBootstrapRunner 的 catch 吞成一行 error。结果是：
**服务全部起来了、健康检查全绿，但没有管理员账号、也没有 .bootstrap-credentials**，
用户会以为"引导功能坏了"。本脚本按真实依赖顺序启动并逐个等待就绪，
把这类"静默错序"变成一次明确的启动失败。

────────────────────────────────────────────────────────────────────────────────
启动顺序与依赖（也是 docs/DEPLOYMENT.md 里那张表的口径）
────────────────────────────────────────────────────────────────────────────────
    阶段 0  基础设施（Docker）      MySQL / Redis / PostgreSQL / RocketMQ
    阶段 1  judge-user      9082    只连 MySQL，无服务依赖
    阶段 2  judge-auth      9081    Feign -> user（引导 + 登录校验）
    阶段 3  judge-problem   9083    只连 MySQL
    阶段 4  judge-submission 9084   Feign -> problem；MQ 投递
    阶段 5  judge-contest   9086    Feign -> submission / problem / user
    阶段 5  judge-worker    9085    消费 MQ（需沙箱镜像）；三者互不阻塞
    阶段 5  judge-ai        9087    唯一响应式；依赖 PostgreSQL(pgvector)
    阶段 6  judge-gateway   9080    路由直达上面全部 —— 必须最后
    阶段 7  judge-web       5174    所有请求经网关

真正**硬**的顺序只有三条：① user 先于 auth；② 基础设施先于任何服务；
③ 网关最后。3–5 之间是并列的，放在一起只是为了输出好读。

用法（仓库根目录）：
    python scripts/start-all.py                    # 基础设施 + 8 服务（默认）
    python scripts/start-all.py --all              # 再拉起 监控栈 + 前端
    python scripts/start-all.py --with-web         # 基础设施 + 服务 + 前端
    python scripts/start-all.py --no-infra         # 基础设施已在跑，只起服务
    python scripts/start-all.py --no-backend --with-monitoring
    python scripts/start-all.py --wait             # 看护模式，常驻并回收子进程
    python scripts/start-all.py --extra judge-worker=9185:9285 --wait
                                                   # 判题机多实例（务必同时设
                                                   # CJ_MQ_CONSUME_THREADS≤核数/实例数）
排除项：
    python scripts/start-all.py --no-infra --no-backend --with-web --wait

⚠️ 看护模式（--wait）必须用「受管后台任务」挂起，父进程退出会连带回收全部子进程
   （宿主 Job Object，见 docs/CONTEXT.md §2）。
⚠️ 本机 `docker compose` 插件**不支持 --env-file**，必须用独立 `docker-compose`
   （见 docs/CONTEXT.md §13.6 新坑 2）。脚本会自动择优并在探测不到时报错退出。
"""

import argparse
import importlib.util
import json
import os
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOG_DIR = os.path.join(ROOT, "logs")
MONITORING_DIR = os.path.join(ROOT, "deploy", "monitoring")
WEB_DIR = os.path.join(ROOT, "judge-web")

# 基础设施待就绪清单：(展示名, 容器名, 宿主探测端口)
INFRA = [
    ("mysql", "codejudge-mysql", 3307),
    ("redis", "codejudge-redis", 6380),
    ("postgres", "codejudge-pg", 5433),
    ("mq-namesrv", "codejudge-mq-namesrv", 9877),
    ("mq-broker", "codejudge-mq-broker", 10921),
]
# 控制台是纯 UI，不参与就绪判定（它没 healthcheck，晚几秒起也不影响链路）
INFRA_OPTIONAL = [("mq-console", "codejudge-mq-console", 18081)]
INFRA_MINIO = ("minio", "codejudge-minio", 9000)

# 各服务就绪探针（actuator 端口 = 服务端口）
HEALTH_PATH = "/actuator/health"
# judge-ai 的 health 带 DataSource(PG) 与 Redis 检查；PG 未就绪时它会 DOWN，
# 这是预期而不是 bug（见 docs/CONTEXT.md §3 的说明），故单列出来降级处理。
DEGRADABLE = {"judge-ai"}


def log(msg=""):
    print(msg, flush=True)


# ---------------------------------------------------------------------------
# 复用 dev-start-backend.py 的派生逻辑（清 SERVER__PORT 注入、DETACHED_PROCESS 等）
# ---------------------------------------------------------------------------
# 文件名带连字符，不能直接 import，故用 importlib 从路径加载。
# **刻意不复制那份逻辑**：宿主注入 SERVER__PORT 的规避、日志分流、进程组设置
# 都是踩过坑的代码，复制会立刻产生两个真相（改了 A 忘了 B）。
def load_backend_launcher():
    path = os.path.join(ROOT, "scripts", "dev-start-backend.py")
    if not os.path.exists(path):
        log(f"x 找不到 {path}")
        sys.exit(1)
    spec = importlib.util.spec_from_file_location("dev_start_backend", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# ---------------------------------------------------------------------------
# 探测工具
# ---------------------------------------------------------------------------
def port_open(port, host="127.0.0.1", timeout=1.0):
    """TCP 连通性探测。用于没有 healthcheck 的容器（namesrv / broker）。"""
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def container_health(container):
    """读容器 healthcheck 状态；容器无 healthcheck 时返回空串。"""
    out = subprocess.run(
        ["docker", "inspect", "-f", "{{if .State.Health}}{{.State.Health.Status}}{{end}}", container],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if out.returncode != 0:
        return None  # 容器不存在
    return (out.stdout or "").strip()


def container_state(container):
    out = subprocess.run(
        ["docker", "inspect", "-f", "{{.State.Status}}", container],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if out.returncode != 0:
        return None
    return (out.stdout or "").strip()


def container_tail(container, lines=8):
    """容器最近日志（排障用；只取尾部，避免刷屏）。"""
    out = subprocess.run(["docker", "logs", "--tail", str(lines), container],
                         capture_output=True, text=True, encoding="utf-8", errors="replace")
    return ((out.stdout or "") + (out.stderr or "")).strip()


def http_health(port, timeout=3.0):
    """
    请求 /actuator/health，返回 (state, detail)。
      state ∈ {'up', 'down', 'not-ready'}
    **用标准库而非 curl 子进程**：curl 在 Git Bash 下需要 --noproxy 才能命中 127.0.0.1，
    这个坑不该由每个调用点各自记住（见 docs/CONTEXT.md §2）。
    """
    url = f"http://127.0.0.1:{port}{HEALTH_PATH}"
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            body = json.loads(resp.read().decode("utf-8", "replace") or "{}")
        status = str(body.get("status", "")).upper()
        if status == "UP":
            return "up", body
        return "down", body
    except urllib.error.HTTPError as e:
        # health 端点自身可能返回 503（DOWN 时 Spring 就是这样）
        try:
            body = json.loads(e.read().decode("utf-8", "replace") or "{}")
        except Exception:
            body = {}
        return ("down" if body.get("status") else "not-ready"), body
    except Exception:
        return "not-ready", {}


def wait_infra(timeout, with_storage):
    """等待基础设施就绪。返回失败的组件名列表（空 = 全部就绪）。"""
    targets = list(INFRA)
    if with_storage:
        targets.append(INFRA_MINIO)
    pending = {name: (container, port) for name, container, port in targets}
    deadline = time.time() + timeout
    failed = {}

    while pending and time.time() < deadline:
        for name in list(pending):
            container, port = pending[name]
            health = container_health(container)
            if health is None:
                failed[name] = f"容器 {container} 不存在（docker-compose up 没成功？）"
                pending.pop(name)
                continue
            state = container_state(container)
            if state in ("exited", "dead"):
                failed[name] = f"{container} 状态 {state}"
                pending.pop(name)
                continue
            if health == "healthy":
                log(f"  ✓ {name:12s} healthy")
                pending.pop(name)
                continue
            if health == "unhealthy":
                failed[name] = f"{container} unhealthy"
                pending.pop(name)
                continue
            # 无 healthcheck（或仍在 starting）：退回端口探测
            if not health and port_open(port):
                log(f"  ✓ {name:12s} 端口 {port} 可达（该容器无 healthcheck）")
                pending.pop(name)
        if pending:
            time.sleep(2)

    for name in pending:
        failed[name] = f"等待超时（{timeout}s 内未就绪）"
    return failed


# ---------------------------------------------------------------------------
# Docker Compose：本机必须用独立二进制（插件不支持 --env-file）
# ---------------------------------------------------------------------------
def compose_bin():
    """
    返回 (命令行前缀, 是否支持 --env-file)。

    本机实测：`docker compose`（插件 v2）**不认** `--env-file`（unknown flag），
    只有独立二进制 `docker-compose` 支持（见 docs/CONTEXT.md §13.6 新坑 2）。
    这个差异直接决定监控栈能不能被正确启动，所以必须显式区分，不能想当然。
    """
    exe = shutil.which("docker-compose")
    if exe:
        return [exe], True
    docker = shutil.which("docker")
    if docker:
        log("⚠ 未找到独立 docker-compose，退化用 `docker compose`（插件）。")
        log("  注意：插件不支持 --env-file，监控栈会因此无法带 .env 启动。")
        return [docker, "compose"], False
    log("x 既没有 docker-compose 也没有 docker，无法启动基础设施。")
    sys.exit(1)


def compose_up(workdir, env_file, files=None, profile=None):
    """
    执行 `compose up -d`。

    env_file 只在支持 --env-file 时显式传入；不支持时依赖 compose 自动读取
    **cwd 下的 .env** —— 这要求 workdir 里确实有 .env，否则会静默用默认值
    （Grafana 口令变默认、告警通道变 null，容器照常 healthy、不报任何错）。
    """
    cmd, supports_env_file = compose_bin()
    if supports_env_file and env_file:
        cmd += ["--env-file", env_file]
    elif env_file and not os.path.exists(os.path.join(workdir, ".env")):
        log(f"x {workdir} 下没有 .env，且当前 compose 不支持 --env-file —— 拒绝启动。")
        log("  静默使用默认值会让监控栈的口令/告警通道全变默认值，比直接失败危险得多。")
        return subprocess.CompletedProcess(cmd, 1, "", "missing .env and no --env-file support")
    for f in files or []:
        cmd += ["-f", f]
    if profile:
        cmd += ["--profile", profile]
    cmd += ["up", "-d"]
    return subprocess.run(cmd, cwd=workdir, capture_output=True,
                          text=True, encoding="utf-8", errors="replace")


# ---------------------------------------------------------------------------
# 各阶段
# ---------------------------------------------------------------------------
def phase_infra(args):
    log("── 阶段 0：基础设施（Docker） " + "─" * 40)
    profile = "storage" if args.with_storage else None
    res = compose_up(ROOT, os.path.join(ROOT, ".env"), None, profile)
    if res.returncode != 0:
        log("x docker-compose up 失败：")
        log(((res.stderr or "") + (res.stdout or "")).strip()[-2000:])
        return False

    failed = wait_infra(args.ready_timeout, args.with_storage)
    for name, container, port in INFRA_OPTIONAL:
        if port_open(port):
            log(f"  ✓ {name:12s} 端口 {port} 可达（可选组件）")

    if failed:
        log("")
        for name, why in failed.items():
            log(f"  x {name}: {why}")
            cname = dict((n, c) for n, c, _ in INFRA + ([INFRA_MINIO] if args.with_storage else []))[name]
            tail = container_tail(cname, 8)
            if tail:
                log("    最近日志：")
                for line in tail.splitlines()[-8:]:
                    log(f"      | {line}")
        log("")
        log("基础设施未全部就绪 —— 后面的服务连不上库，先修这个再继续。")
        return False
    log("  基础设施全部就绪。")
    return True


def phase_backend(args, launcher):
    log("")
    log("── 阶段 1–6：后端服务（按依赖顺序） " + "─" * 32)
    # 复用 dev-start-backend 的 --extra 解析：它接受「--extra <spec>」形式，
    # 而本脚本用 nargs="+" 收集，需要把多段 spec 用 "," 拼回单段（spec 内部
    # 也是用 "," 分隔多模块，语义一致）。
    extra_argv = ["--extra", ",".join(args.extra)] if args.extra else []
    extras, leftover = launcher.parse_extra(extra_argv)
    if leftover:
        log(f"⚠ --extra 之外的未知参数被忽略：{leftover}")

    procs = []
    for module, port in launcher.SERVICES.items():
        proc = launcher.spawn(module, port)
        if proc:
            procs.append((module, port, proc))
        time.sleep(0.3)
    for module, port in extras:
        proc = launcher.spawn(module, port)
        if proc:
            procs.append((f"{module}:{port}", port, proc))
        time.sleep(0.3)

    if not procs:
        log("x 一个服务都没起来（jar 是否已构建？mvn clean install -DskipTests）")
        return [], []

    # 逐个等待就绪：按启动顺序串行探活，避免 8 个并发轮询把日志刷乱
    deadline = time.time() + args.ready_timeout
    pending = {name: port for name, port, _ in procs}
    ready, degraded, failed = [], [], {}
    while pending and time.time() < deadline:
        for name in list(pending):
            port = pending[name]
            state, body = http_health(port)
            if state == "up":
                log(f"  ✓ {name:22s} :{port} UP")
                ready.append(name)
                pending.pop(name)
            elif state == "down":
                name_base = name.split(":")[0]
                if name_base in DEGRADABLE:
                    log(f"  ~ {name:22s} :{port} DOWN（响应正常但依赖未就绪，"
                        f"components={list((body.get('components') or {}).keys())}）")
                    degraded.append(name)
                    pending.pop(name)
                else:
                    detail = ", ".join(f"{k}={v.get('status')}" for k, v in
                                       (body.get("components") or {}).items())
                    failed[name] = f":{port} DOWN（{detail or '无组件信息'}）"
                    pending.pop(name)
        if pending:
            time.sleep(2)

    for name in pending:
        failed[name] = f":{pending[name]} 超时未响应（{args.ready_timeout}s）"
    # 统一返回全部子进程（看护模式要盯着每一个，包括启动失败但进程还在的），
    # 失败明细单独给，避免"过滤掉失败项导致看护漏掉它"。
    return procs, failed or []


def phase_monitoring(args):
    log("")
    log("── 阶段 7：监控栈（Prometheus / Alertmanager / Grafana） " + "─" * 22)
    if not os.path.isdir(MONITORING_DIR):
        log("x 找不到 deploy/monitoring，跳过")
        return False
    # ⚠️ --env-file 必带：该目录下没有 .env，漏带会**静默**退回 compose 默认值
    #    （Grafana 密码变默认、告警通道变 null），容器照样 healthy、不报任何错。
    res = compose_up(MONITORING_DIR, os.path.join(MONITORING_DIR, "..", "..", ".env"),
                     ["docker-compose.monitoring.yml"])
    if res.returncode != 0:
        log("x 监控栈启动失败：")
        log(((res.stderr or "") + (res.stdout or "")).strip()[-1500:])
        return False
    for name, port in (("prometheus", 9090), ("alertmanager", 9093), ("grafana", 3001)):
        ok = False
        for _ in range(30):
            if port_open(port):
                ok = True
                break
            time.sleep(1)
        log(f"  {'✓' if ok else 'x'} {name:14s} :{port}")
    return True


def phase_web(args):
    log("")
    log("── 阶段 8：前端（Vite dev server） " + "─" * 36)
    node = shutil.which("node")
    if not node:
        log("x PATH 里没有 node，跳过前端")
        return None
    vite = os.path.join(WEB_DIR, "node_modules", "vite", "bin", "vite.js")
    if not os.path.exists(vite):
        log(f"x 找不到 {vite}（先在 judge-web 下 npm install）")
        return None
    if port_open(5174):
        log("  ! 5174 已在监听：前端看起来已经在跑，跳过（避免 --strictPort 冲突退出）")
        return None
    os.makedirs(LOG_DIR, exist_ok=True)
    log_path = os.path.join(LOG_DIR, "judge-web.dev.log")
    log_file = open(log_path, "ab", buffering=0)
    creationflags = 0
    if os.name == "nt":
        creationflags = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
    proc = subprocess.Popen(
        [node, vite, "--port", "5174", "--strictPort"],
        cwd=WEB_DIR, stdout=log_file, stderr=subprocess.STDOUT,
        creationflags=creationflags, close_fds=True,
    )
    for _ in range(30):
        if port_open(5174):
            log(f"  ✓ judge-web      :5174 已就绪  pid={proc.pid}  日志: logs/judge-web.dev.log")
            return proc
        time.sleep(1)
    log(f"  x judge-web :5174 超时未监听，看 logs/judge-web.dev.log  pid={proc.pid}")
    return proc


# ---------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(
        description="CodeJudge 一键启动（基础设施 + 服务 + 可选监控/前端）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--all", action="store_true", help="= 基础设施 + 服务 + 监控栈 + 前端")
    parser.add_argument("--with-monitoring", action="store_true", help="额外启动监控栈")
    parser.add_argument("--with-web", action="store_true", help="额外启动前端 dev server")
    parser.add_argument("--with-storage", action="store_true", help="额外启动 MinIO（storage profile）")
    parser.add_argument("--no-infra", action="store_true", help="跳过基础设施（已在跑时用）")
    parser.add_argument("--no-backend", action="store_true", help="跳过 8 个后端服务")
    parser.add_argument("--wait", action="store_true", help="看护模式：父进程常驻，退出时回收子进程")
    parser.add_argument("--ready-timeout", type=int, default=240,
                        help="每个阶段的就绪等待上限（秒），默认 240")
    parser.add_argument("--extra", nargs="+", default=[],
                        metavar="SPEC", help="透传给 dev-start-backend 的多实例参数，如 judge-worker=9185:9285")
    args = parser.parse_args()

    if args.all:
        args.with_monitoring = True
        args.with_web = True

    os.makedirs(LOG_DIR, exist_ok=True)
    os.makedirs(os.path.join(LOG_DIR, "tmp"), exist_ok=True)

    started = time.time()
    log("CodeJudge 一键启动")
    log("=" * 68)

    if not args.no_infra and not phase_infra(args):
        sys.exit(1)

    launcher = load_backend_launcher()
    procs, failed = ([], [])
    if not args.no_backend:
        procs, failed = phase_backend(args, launcher)

    web_proc = None
    if args.with_monitoring:
        phase_monitoring(args)
    if args.with_web:
        web_proc = phase_web(args)

    log("")
    log("=" * 68)
    if failed:
        log(f"启动结束（{time.time() - started:.0f}s）—— 有 {len(failed)} 个服务未就绪：")
        for name, why in failed:
            log(f"  x {name} {why}")
            base = name.split(":")[0]
            log_path = os.path.join(LOG_DIR, f"{base}.dev.log")
            log(f"    日志：{log_path}")
        log("")
        log("常见原因：jar 未构建 / 端口被占 / .env 里的口令与数据卷不一致（见 docs/CONTEXT.md §13.9）")
    else:
        log(f"启动完成（{time.time() - started:.0f}s）")
        log("")
        log("  网关统一入口   http://localhost:9080")
        log("  接口文档       http://localhost:9080/doc.html")
        if args.with_web:
            log("  前端           http://localhost:5174")
        if args.with_monitoring:
            log("  Prometheus     http://localhost:9090   Grafana http://localhost:3001")

    if args.wait:
        watched = [(name, proc) for name, _port, proc in procs]
        if web_proc:
            watched.append(("judge-web", web_proc))
        if not watched:
            log("没有可看护的子进程，直接退出。")
            return
        log("")
        log(f"[--wait] 看护模式：本进程驻留期间上述 {len(watched)} 个子进程存活；"
            "结束本进程将回收全部服务")
        try:
            while True:
                time.sleep(5)
                for name, proc in watched:
                    if proc.poll() is not None:
                        log(f"[--wait] {name} 已退出，exit={proc.returncode}"
                            f"（日志：logs/{name.split(':')[0]}.dev.log）")
        except KeyboardInterrupt:
            log("\n[--wait] 收到退出信号，终止全部子进程……")
            for name, proc in watched:
                if proc.poll() is None:
                    proc.kill()
                    log(f"  已终止 {name}")
            if not args.no_infra:
                log("  提示：基础设施容器仍在运行（如需停止：docker-compose down）")


if __name__ == "__main__":
    main()
