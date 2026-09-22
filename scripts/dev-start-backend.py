#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CodeJudge 后端服务启动器（Windows / Python）。

为什么用它而不是 dev-start-backend.ps1：
  1. Git Bash 里 nohup 启动的子 JVM 会随 shell 退出被整体回收（实测 2026-09-20）；
  2. 本机 .sh 被路由到 wsl.exe 撞黑名单；
  3. WorkBuddy 宿主环境变量同时含 Path 与 PATH 两个大小写变体，
     PowerShell 5.1 的 Start-Process 构建环境字典时报
     “已添加项。字典中的关键字: Path / PATH”（实测 2026-09-20），
     本脚本用 subprocess.Popen + DETACHED_PROCESS 绕开该问题。

用法（仓库根目录）：
    python scripts/dev-start-backend.py                      # 启动全部服务（含 P4 judge-contest）
    python scripts/dev-start-backend.py judge-submission     # 只启动指定模块
"""

import os
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOG_DIR = os.path.join(ROOT, "logs")

# 模块 -> (默认端口, 启动顺序即依赖顺序：auth/user/problem/submission/contest/worker/gateway)
# judge-contest 放在 judge-submission 之后：竞赛提交校验依赖 contest 的内部上下文接口，
# 而 contest 的终榜重建又反向依赖 submission，二者互相引用，启动顺序只影响「首次调用是否立即成功」。
# judge-ai 排在 gateway 之前：网关路由 /ai/** 指向 9087，服务未起时网关首次转发会 502。
# ⚠️ judge-ai 是**纯响应式（Netty）**服务，与其余 7 个 Servlet 服务不同栈，
#    靠 application.yml 的 spring.main.web-application-type=reactive 显式指定，
#    不能依赖类路径推断（knife4j 会把 spring-webmvc 带进来）。
SERVICES = {
    "judge-auth": 9081,
    "judge-user": 9082,
    "judge-problem": 9083,
    "judge-submission": 9084,
    "judge-contest": 9086,
    "judge-worker": 9085,
    "judge-ai": 9087,
    "judge-gateway": 9080,
}

# 宿主注入的端口变量会被 Spring 松散绑定覆盖 server.port（实测确证，见 docs/CONTEXT.md §7）
REMOVE_ENV = ["SERVER__PORT", "SERVER__HOST", "SERVER_PORT", "SERVER_HOST"]


def build_env():
    env = dict(os.environ)
    for key in REMOVE_ENV:
        env.pop(key, None)
        env.pop(key.lower(), None)
    env["JAVA_TOOL_OPTIONS"] = "-Djava.io.tmpdir=" + os.path.join(ROOT, "logs", "tmp")
    return env


def spawn(module, port):
    jar = os.path.join(ROOT, module, "target", module + ".jar")
    if not os.path.exists(jar):
        print(f"  x {module}: jar 不存在（先执行 mvn clean install -DskipTests）")
        return None
    log_path = os.path.join(LOG_DIR, module + ".dev.log")
    log = open(log_path, "ab", buffering=0)
    creationflags = 0
    if os.name == "nt":
        # DETACHED_PROCESS：脱离父进程控制台；CREATE_NEW_PROCESS_GROUP：独立进程组，父退出不回收
        creationflags = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
    proc = subprocess.Popen(
        ["java", "-jar", jar, "--server.port=" + str(port)],
        stdout=log, stderr=subprocess.STDOUT,
        cwd=ROOT, env=build_env(), creationflags=creationflags, close_fds=True,
    )
    print(f"  -> {module} :{port}  pid={proc.pid}  日志: logs/{module}.dev.log")
    return proc


def main():
    os.makedirs(LOG_DIR, exist_ok=True)
    os.makedirs(os.path.join(LOG_DIR, "tmp"), exist_ok=True)

    wait_mode = "--wait" in sys.argv
    selected = [a for a in sys.argv[1:] if a != "--wait"] or list(SERVICES.keys())
    unknown = [m for m in selected if m not in SERVICES]
    if unknown:
        print(f"未知模块：{unknown}；可选：{list(SERVICES.keys())}")
        sys.exit(2)

    print("启动服务（已清除 SERVER__PORT/SERVER__HOST 注入）：")
    procs = []
    for module in selected:
        proc = spawn(module, SERVICES[module])
        if proc:
            procs.append((module, proc))
        time.sleep(0.3)

    if not procs:
        sys.exit(1)
    print(f"\n已启动 {len(procs)} 个服务。就绪判断：curl http://localhost:<port>/actuator/health")
    print("网关统一入口：http://localhost:9080   接口文档：http://localhost:9080/doc.html")

    # 看护模式：父进程驻留（某些宿主的 Job Object 会在父进程退出时回收全部子进程，
    # 实测 2026-09-20 —— 用 run_in_background 挂起本脚本即可常驻）
    if wait_mode:
        print("[--wait] 看护模式：Ctrl+C / 结束本进程将回收全部服务")
        try:
            while True:
                time.sleep(5)
                for module, proc in procs:
                    if proc.poll() is not None:
                        print(f"[--wait] {module} 已退出，exit={proc.returncode}")
        except KeyboardInterrupt:
            print("\n[--wait] 收到退出信号，终止全部服务……")
            for module, proc in procs:
                if proc.poll() is None:
                    proc.kill()
                    print(f"  已终止 {module}")


if __name__ == "__main__":
    main()
