# -*- coding: utf-8 -*-
"""
从本地 mvn 产物(target/*.jar)直接构建各服务的**运行时**镜像。

适用场景：构建机网络受限、拉不动 maven 构建器镜像时的降级路径。
产出的镜像与多阶段 Dockerfile 的 runtime 阶段等价（同 base / 同 ENTRYPOINT /
同 HEALTHCHECK），tag 同为 codejudge/<mod>:latest，可直接被
`docker compose --profile app up -d` 使用。

⚠️ 例外：judge-worker 的多阶段 Dockerfile 额外装了 docker.io（派发沙箱容器用）。
本脚本**不装**（数百 MB 的 apt 下载，冒烟镜像不值得）——预构建 worker 只能起 HTTP
服务，无法派发沙箱。容器化判题（阻塞项7）必须用多阶段 Dockerfile 构建的镜像。

前提：先 `mvn -DskipTests package`（jar 在 <mod>/target/<mod>.jar）。
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

TPL = """FROM eclipse-temurin:21-jre
RUN apt-get update \\
    && apt-get install -y --no-install-recommends curl \\
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 1001 --shell /usr/sbin/nologin judge \\
    && mkdir -p /app && chown judge:judge /app
USER judge
WORKDIR /app
COPY {jar} /app/app.jar
EXPOSE {port}
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \\
  CMD curl -fsS http://127.0.0.1:{port}/actuator/health || exit 1
"""


def build(mod, port):
    jar = ROOT / mod / "target" / f"{mod}.jar"
    if not jar.exists():
        print(f"[SKIP] {mod}: {jar} 不存在（先 mvn package）")
        return False
    dockerfile = TPL.format(jar=f"{mod}/target/{mod}.jar", port=port)
    cmd = ["docker", "build", "-t", f"codejudge/{mod}:latest", "-f", "-", str(ROOT)]
    p = subprocess.run(cmd, input=dockerfile.encode(), cwd=str(ROOT))
    return p.returncode == 0


def main():
    only = sys.argv[1:] or list(MODS)
    failed = []
    for m in only:
        print(f"\n===== {m} (:{MODS[m]}) =====")
        ok = build(m, MODS[m])
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
