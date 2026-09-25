#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CodeJudge 后端服务启动器（Windows / Python）。

为什么用它而不是 dev-start-backend.ps1：
  1. Git Bash 里 nohup 启动的子 JVM 会随 shell 退出被整体回收（实测 2026-09-20）；
  2. 本机 .sh 被路由到 wsl.exe 撞黑名单；
  3. 宿主环境变量同时含 Path 与 PATH 两个大小写变体，
     PowerShell 5.1 的 Start-Process 构建环境字典时报
     “已添加项。字典中的关键字: Path / PATH”（实测 2026-09-20），
     本脚本用 subprocess.Popen + DETACHED_PROCESS 绕开该问题。

用法（仓库根目录）：
    python scripts/dev-start-backend.py                      # 启动全部服务（含 P4 judge-contest）
    python scripts/dev-start-backend.py judge-submission     # 只启动指定模块
    python scripts/dev-start-backend.py --extra judge-worker=9185:9285
                                                             # 额外起 2 个判题机实例
                                                             # （1 vs N worker 扩容对比用；
                                                             #   端口用 ":" 分隔，多模块用 "," 分隔）

为什么需要 --extra：判题机是唯一**可能**需要水平扩容的服务，而本脚本的 SERVICES 是
「模块 -> 唯一端口」的字典，同模块天然只能起一个。--extra 让同模块多实例不必手工 java -jar
（手工起必须自己 unset SERVER__PORT，见 docs/CONTEXT.md §3 第 13 条）。

 多实例判题必须同时给 `CJ_MQ_CONSUME_THREADS` 设上限（2026-09-22 实测，见 docs/PERF.md §3.7）：
    CJ_MQ_CONSUME_THREADS=4 python scripts/dev-start-backend.py --extra judge-worker=9185:9285 --wait
   不设 → 每实例 20 个消费线程 = 20 个并发 `docker run`，三实例 60 并发会让**容器冷启动**
   超过沙箱墙钟预算 → **正确解被判假 TLE**（实测抽样 18/25）。经验公式：
   实例数 × CJ_MQ_CONSUME_THREADS ≤ 宿主机 CPU 核数。
   另：本机实测**判题吞吐不随实例数增长**（1 实例 0.94 题/s，3 实例 0.96 题/s），
   瓶颈是每任务 5 个容器的启停开销 —— 扩容前先读 §3.7 的结论。
"""

import os
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOG_DIR = os.path.join(ROOT, "logs")

# 模块 -> 默认端口。**字典的插入顺序即启动顺序**（Python 3.7+ 保证），
# 顺序依据是「服务间 Feign 调用方向」，不是字母序：
#
#   1. judge-user      无服务依赖（只连 MySQL）。它的账号表是所有人的基础。
#   2. judge-auth      Feign -> judge-user。**必须排在 user 之后**：首个管理员的
#                      安全引导是 ApplicationRunner，启动即调 UserClient 查
#                      adminExists / 创建管理员；user 没起来时引导会被
#                      AdminBootstrapRunner 的 catch 吞掉（日志一行 error），
#                      表现为「服务都起来了，但没有管理员账号、也没有凭据文件」。
#   3. judge-problem   只连 MySQL；但被 submission / contest Feign 调用，故先起。
#   4. judge-submission Feign -> problem；提交时校验题目存在与可见性。
#   5. judge-contest   Feign -> submission（终榜回填）、problem（建赛校验）、user。
#                      与 submission 互相引用，顺序只影响「首次调用是否立即成功」。
#   6. judge-worker    消费 MQ 的 CREATED/RETRY；不阻塞启动，但要判题就得在。
#   7. judge-ai        唯一响应式（WebFlux + Netty）服务，依赖 PG；排在网关之前，
#                      否则网关首次转发 /ai/** 会 502。
#   8. judge-gateway   统一入口，路由直达上面全部 —— 必须最后起。
SERVICES = {
    "judge-user": 9082,
    "judge-auth": 9081,
    "judge-problem": 9083,
    "judge-submission": 9084,
    "judge-contest": 9086,
    "judge-worker": 9085,
    "judge-ai": 9087,
    "judge-gateway": 9080,
}

# 网关路由的 uri 是 ${GW_*_URI} 静态直连；服务间用 SimpleDiscoveryClient 的
# simple.instances。两者的排障口诀见 docs/CONTEXT.md §4.5：
#   「网关 502/404」查 GW_*_URI；「服务间 LoadBalancer 无可用实例」查 simple.instances。

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
    # 非默认端口（--extra 起的额外实例）日志单独成文件，否则 N 个实例共写同一个
    # logs/<module>.dev.log，时间轴交错、按实例排查时完全不可读（2026-09-22 实测踩到）。
    log_name = module if port == SERVICES.get(module) else f"{module}-{port}"
    log_path = os.path.join(LOG_DIR, log_name + ".dev.log")
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


def parse_extra(argv):
    """解析 `--extra module=port[:port...][,module=port...]`，返回 ([module, port], 剩余参数)。

    格式用 ":" 分隔同模块多端口、"," 分隔多模块 —— 不用 "," 同时承担两种分隔，
    否则 `a=1,2` 到底是「两个模块」还是「一个模块两个端口」就产生了歧义。
    """
    argv = list(argv)
    extras = []
    while "--extra" in argv:
        i = argv.index("--extra")
        spec = argv[i + 1] if i + 1 < len(argv) else ""
        argv = argv[:i] + argv[i + 2:]
        for item in filter(None, (s.strip() for s in spec.split(","))):
            if "=" not in item:
                print(f"错误：--extra 格式应为 module=port[:port...]，收到 {item!r}")
                sys.exit(2)
            module, ports = item.split("=", 1)
            if module not in SERVICES:
                print(f"错误：--extra 里的未知模块 {module!r}；可选：{list(SERVICES.keys())}")
                sys.exit(2)
            for p in ports.split(":"):
                try:
                    extras.append((module, int(p)))
                except ValueError:
                    print(f"错误：--extra 端口不是整数：{p!r}")
                    sys.exit(2)
    return extras, argv


def main():
    os.makedirs(LOG_DIR, exist_ok=True)
    os.makedirs(os.path.join(LOG_DIR, "tmp"), exist_ok=True)

    extras, argv = parse_extra(sys.argv[1:])
    wait_mode = "--wait" in argv
    selected = [a for a in argv if a != "--wait"] or list(SERVICES.keys())
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

    # 额外实例：同一 jar 换端口再起一个（module 名相同，靠 --server.port 区分）
    for module, port in extras:
        proc = spawn(module, port)
        if proc:
            procs.append((f"{module}:{port}", proc))
        time.sleep(0.3)

    if not procs:
        sys.exit(1)
    print(f"\n已启动 {len(procs)} 个服务。就绪判断：curl http://localhost:<port>/actuator/health")
    if extras:
        n_worker = (1 if "judge-worker" in selected else 0) + \
                   sum(1 for m, _ in extras if m == "judge-worker")
        print(f"额外实例：{', '.join(f'{m}:{p}' for m, p in extras)}"
              f"  → 判题机实例共 {n_worker} 个")
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
