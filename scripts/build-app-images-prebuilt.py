# -*- coding: utf-8 -*-
"""
从本地 mvn 产物(target/*.jar)直接构建各服务的**运行时**镜像。

适用场景：构建机网络受限、拉不动 maven 构建器镜像时的降级路径。
产出的镜像与多阶段 Dockerfile 的 runtime 阶段等价（同 base / 同 ENTRYPOINT /
同 HEALTHCHECK），tag 同为 codejudge/<mod>:latest，可直接被
`docker compose --profile app up -d` 使用。

⚠️ 例外：judge-worker 的多阶段 Dockerfile 额外装了 docker.io（派发沙箱容器用）。
本脚本默认不装（数百 MB 的 apt 下载，冒烟镜像不值得）——预构建 worker 只能起 HTTP
服务，无法派发沙箱。容器化判题（阻塞项7）传 `--worker-docker-cli` 参数：
apt 装 docker.io（Ubuntu archive 宿主可达，约 +150MB），配合 compose 的
group_add 与 docker.sock 挂载即可在容器内派发沙箱（2026-10-01 实测路径）。

用法：
  python scripts/build-app-images-prebuilt.py                     # 8 服务冒烟镜像
  python scripts/build-app-images-prebuilt.py --worker-docker-cli # worker 含 docker CLI
  python scripts/build-app-images-prebuilt.py judge-worker --worker-docker-cli
"""
import subprocess
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent

MODS = {
    "judge-gateway": 9080,
    "judge-auth": 9081,
    "judge-user": 9082,
    "judge-problem": 9083,
    "judge-submission": 9084,
    "judge-worker": 9085,
    "judge-contest": 9086,
    "judge-ai": 9087,
}

_TPL_BODY = """RUN useradd --system --uid 1001 --shell /usr/sbin/nologin judge \\
    && mkdir -p /app && chown judge:judge /app
USER judge
WORKDIR /app
COPY {jar} /app/app.jar
EXPOSE {port}
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \\
  CMD curl -fsS http://127.0.0.1:{port}/actuator/health || exit 1
"""

TPL = """FROM eclipse-temurin:21-jre
RUN apt-get update \\
    && apt-get install -y --no-install-recommends curl \\
    && rm -rf /var/lib/apt/lists/*
""" + _TPL_BODY

# worker 容器化沙箱变体：docker.io 提供派发沙箱所需的 docker CLI
# （dockerd 不在容器内跑，仍经挂载的 /var/run/docker.sock 走宿主 daemon）
WORKER_DOCKER_CLI_TPL = """FROM eclipse-temurin:21-jre
RUN apt-get update \\
    && apt-get install -y --no-install-recommends curl docker.io \\
    && rm -rf /var/lib/apt/lists/*
""" + _TPL_BODY


def build(mod, port, tpl):
    jar = ROOT / mod / "target" / f"{mod}.jar"
    if not jar.exists():
        print(f"[SKIP] {mod}: {jar} 不存在（先 mvn package）")
        return False
    dockerfile = tpl.format(jar=f"{mod}/target/{mod}.jar", port=port)
    cmd = ["docker", "build", "-t", f"codejudge/{mod}:latest", "-f", "-", str(ROOT)]
    p = subprocess.run(cmd, input=dockerfile.encode(), cwd=str(ROOT))
    return p.returncode == 0


def main():
    argv = sys.argv[1:]
    worker_docker_cli = "--worker-docker-cli" in argv
    only = [a for a in argv if not a.startswith("--")] or list(MODS)
    failed = []
    for m in only:
        tpl = WORKER_DOCKER_CLI_TPL if (m == "judge-worker" and worker_docker_cli) else TPL
        print(f"\n===== {m} (:{MODS[m]}){'' if tpl is TPL else ' +docker-cli'} =====")
        ok = build(m, MODS[m], tpl)
        print(f"[{'OK' if ok else 'FAIL'}] {m}")
        if not ok:
            failed.append(m)
    if failed:
        print(f"\n失败：{', '.join(failed)}")
        return 1
    print("\n全部运行时镜像构建完成")
    return 0


if __name__ == "__main__":
    sys.exit(main())
