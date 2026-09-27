#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CodeJudge 综合启动入口（参照 zx-learn 的「仓库根唯一入口」模式：startup.sh → dev-start-backend.ps1）。

用法（仓库根目录）：
    python startup.py                  # 基础设施 + 全部 8 个后端服务（最常用）
    python startup.py --all            # 再拉起 监控栈 + 前端
    python startup.py --with-web       # 基础设施 + 服务 + 前端
    python startup.py --no-infra       # 基础设施已在跑，只起服务
    python startup.py --wait           # 看护模式：父进程常驻，退出时回收全部子进程
    python startup.py --extra judge-worker=9185:9285 --wait
                                       # 判题机多实例（须同时压低 CJ_MQ_CONSUME_THREADS）
    其余参数原样透传给 scripts/start-all.py（python startup.py --help 可看全量）。

Windows 资源管理器双击：startup.cmd（内部转发到本脚本）。

本脚本只做三件事，编排逻辑一概不复刻（单一真相在 scripts/start-all.py，它又复用
scripts/dev-start-backend.py —— 两处都是踩坑沉淀，复制会立刻产生两个真相）：
    ① 预检快速失败：Docker / Java / .env / 8 个 jar，缺哪样指哪样，给出确切补救命令；
    ② 委托 scripts/start-all.py 并透传全部参数；
    ③ 透传退出码，失败时指路日志。
"""

import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
START_ALL = os.path.join(ROOT, "scripts", "start-all.py")

# 8 个后端服务的 jar 落点（与 dev-start-backend.py 的 SERVICES 一一对应）
BACKEND_MODULES = [
    "judge-user", "judge-auth", "judge-problem", "judge-submission",
    "judge-contest", "judge-worker", "judge-ai", "judge-gateway",
]


def fail(msg):
    print(f"\n[启动失败] {msg}", flush=True)
    sys.exit(1)


def check(label, ok, hint=""):
    mark = "OK " if ok else "MISSING"
    print(f"  [{'✓' if ok else '✗'}] {label}{'' if ok else ' —— ' + hint}", flush=True)
    return ok


def preflight(args):
    """逐项预检；任何一项不过立即给出明确的错误与补救命令。"""
    print("CodeJudge 综合启动（预检）")
    print("=" * 68)

    if sys.version_info < (3, 8):
        fail(f"Python 版本过低（当前 {sys.version.split()[0]}），需要 3.8+。")

    # ---- 委托脚本存在性 ----
    if not os.path.exists(START_ALL):
        fail(f"找不到编排脚本 {START_ALL}，仓库不完整，请重新拉取。")

    no_infra = "--no-infra" in args
    no_backend = "--no-backend" in args
    help_only = "--help" in args or "-h" in args

    if help_only:
        return  # 用法查询不做环境预检，直接透传

    # ---- Docker（起基础设施才需要）----
    if not no_infra:
        docker = shutil.which("docker")
        if not docker:
            fail("未找到 docker 命令。请先安装并启动 Docker Desktop（本项目依赖 "
                 "MySQL/Redis/PostgreSQL/RocketMQ 容器）。")
        probe = subprocess.run([docker, "info", "--format", "{{.ServerVersion}}"],
                               capture_output=True, text=True, timeout=15)
        if probe.returncode != 0:
            fail("Docker 已安装但守护进程不可达（大概率 Docker Desktop 未启动）。"
                 "请先启动 Docker Desktop，再运行本脚本。")

    # ---- Java ----
    if not no_backend:
        if not shutil.which("java"):
            fail("未找到 java 命令。本项目需要 JDK 21（Temurin 21.0.7 已验证），"
                 "请将其 bin 目录加入 PATH。")

        # ---- .env（compose 与服务口令都从这流转；缺失时 compose 会报一堆隐晦错误）----
        if not no_infra and not os.path.exists(os.path.join(ROOT, ".env")):
            fail("仓库根目录缺 .env 文件（基础设施口令 / CORS / 管理员引导变量都在里面）。"
                 "参照 .env.example 配置一份后再启动。")

        # ---- jar 预检：逐个报缺，一次性给全，而不是起来一个失败一个 ----
        missing = [m for m in BACKEND_MODULES
                   if not os.path.exists(os.path.join(ROOT, m, "target", f"{m}.jar"))]
        if missing:
            print()
            fail("以下服务的 jar 未构建（改过代码/配置后必须重新打包，只改 yml 不重新打包"
                 "【不会】生效——2026-09-27 实测旧 jar 陷阱）：\n"
                 "    " + "\n    ".join(missing) +
                 "\n  补救：在仓库根目录执行\n"
                 "      mvn.cmd -DskipTests package\n"
                 "  然后重新运行本脚本。")

    print()


def main():
    args = sys.argv[1:]

    # 与 start-all.py 相同的 cwd 约定：相对路径一律按仓库根解析
    os.chdir(ROOT)

    preflight(args)

    print("=" * 68)
    print("预检通过，移交编排脚本 scripts/start-all.py …\n", flush=True)

    rc = subprocess.call([sys.executable, START_ALL] + args)

    print()
    if rc == 0:
        print("综合启动流程结束。日志在 logs/<模块>.dev.log；"
              "停止服务 = 结束看护进程（--wait 模式）或逐个停 JVM。")
    else:
        print(f"[启动失败] 编排脚本退出码 {rc}。排查顺序：\n"
              "  1) 上方各阶段输出里的 x 行（哪个组件没就绪写得明确）；\n"
              "  2) logs/<模块>.dev.log 里该服务启动报错的最后 50 行；\n"
              "  3) 端口占用：netstat -ano | findstr :9080；\n"
              "  4) .env 口令与数据卷不一致（见 docs/CONTEXT.md §13.9）。")
    sys.exit(rc)


if __name__ == "__main__":
    main()
