#!/usr/bin/env bash
# ==========================================================
# CodeJudge —— 后端一键启动（本地开发）
# ----------------------------------------------------------
# 用法：
#   bash scripts/dev-start-backend.sh                 # 启动 P1 全部服务
#   bash scripts/dev-start-backend.sh judge-auth judge-user judge-gateway
#                                                     # 只启动指定服务（最小链路）
#
# 为什么需要这个脚本？（后端"无法启动"的根因）
# ----------------------------------------------------------
# 某些宿主/IDE 环境（例如 WorkBuddy 沙箱终端）会向子进程注入：
#     SERVER__PORT=<宿主自身占用的端口>
#     SERVER__HOST=127.0.0.1
# Spring Boot 的"松散绑定"会把 SERVER__PORT 解析为 server.port，
# 优先级高于 application.yml（OS 环境变量 > 配置文件），于是所有服务
# 都去抢占宿主那个端口 → 端口冲突 → APPLICATION FAILED TO START。
# 表现：不指定端口时必然启动失败，报 "Identify and stop the process
#        that's listening on port <xxxxx>"。
#
# 已实测确证（2026-09-20）：在 WorkBuddy 终端内转储子 JVM 环境，
#     SERVER__PORT = 56298   ← 与宿主进程实际监听端口一致
#     SERVER__HOST = 127.0.0.1
# 且不 unset 直接 `java -jar` 时，Tomcat 确实绑定到 56298 而非 yml 的 9082。
#
# 本脚本在启动前清除这两个注入变量（含单下划线变体 SERVER_PORT/SERVER_HOST），
# 并把 java.io.tmpdir 指向仓库内可写目录（沙箱下 %TMP% 可能指向 C:\Windows\
# 导致 AccessDenied），从而保证各服务按自己 application.yml 的端口正常启动。
#
# ⚠ 绕开本脚本直接 `java -jar` 时，务必二选一，否则必踩此坑：
#     ① 先 `unset SERVER__PORT SERVER__HOST SERVER_PORT SERVER_HOST`
#     ② 或显式传参：`java -jar target/judge-xxx.jar --server.port=9082`
#        （命令行参数优先级高于环境变量，最稳）
#
# 另外 Maven 必须用 **mvn.cmd** 而不是 bin/mvn：
#   Git Bash 下执行 bin/mvn（bash 包装脚本）会因路径转换问题报
#   "找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher"。
# ----------------------------------------------------------
# 可用环境变量覆盖：
#   JAVA_HOME   指定 JDK 21 根目录
#   MVN         指定 mvn 可执行文件（默认自动探测 mvn.cmd）
# ==========================================================
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# ---------- 定位 Maven ----------
if [ -z "${MVN:-}" ]; then
  if [ -x "/d/1/apache-maven-3.9.6/bin/mvn.cmd" ]; then
    MVN="/d/1/apache-maven-3.9.6/bin/mvn.cmd"
  elif command -v mvn.cmd >/dev/null 2>&1; then
    MVN="$(command -v mvn.cmd)"
  elif command -v mvn >/dev/null 2>&1; then
    MVN="$(command -v mvn)"
  else
    echo "错误：未找到 Maven，请设置环境变量 MVN=/path/to/mvn.cmd" >&2
    exit 1
  fi
fi

TMP_LOCAL="$ROOT/logs/tmp"
mkdir -p "$TMP_LOCAL" logs

# java.io.tmpdir 必须传 **Windows 风格路径**：
# Git Bash 下 $ROOT 形如 /d/1/CodeJudge，而 java.io.tmpdir 是给 JVM 用的，
# JVM 会把 /d/1/... 解析成 \d\1\... 从而找不到目录，spring-boot:run 直接失败：
#   Could not build classpath: \d\1\CodeJudge\logs\tmp\spring-boot-xxxx.argfile
# 用 pwd -W 取真实盘符路径（D:/1/CodeJudge）；非 MSYS 环境（Linux/macOS）回退为原路径。
if command -v pwd >/dev/null 2>&1 && pwd -W >/dev/null 2>&1; then
  TMP_FOR_JVM="$(pwd -W)/logs/tmp"
else
  TMP_FOR_JVM="$TMP_LOCAL"
fi

# 服务清单；后续阶段把新模块追加进来即可
ALL=(judge-auth judge-user judge-problem judge-submission judge-worker judge-gateway)
# P4+：judge-contest
# P5+：judge-ai

MODULES=("$@")
[ ${#MODULES[@]} -eq 0 ] && MODULES=("${ALL[@]}")

# ---------- 启动前自检 ----------
if [ ! -f "$ROOT/.env" ]; then
  echo "⚠ 未发现 .env —— 服务会因缺少 MYSQL_PASSWORD / REDIS_PASSWORD / CJ_JWT_SECRET 启动失败。"
  echo "  请先执行：cp .env.example .env 并填写密码后重试。"
  echo
fi

echo "仓库根目录: $ROOT"
echo "Maven     : $MVN"
echo "JDK       : ${JAVA_HOME:-<继承当前环境>}"
echo "启动服务  : ${MODULES[*]}"
echo "----------------------------------------------------------"

for m in "${MODULES[@]}"; do
  if [ ! -d "$ROOT/$m" ]; then
    echo "  ✗ 模块不存在：$m（已跳过）"
    continue
  fi
  # 关键：unset 清除宿主注入的 SERVER__PORT / SERVER__HOST（不要用 `env -u`：
  # PATH 里的 ~/.local/bin/env 是只改 PATH 的存根脚本、没有 exec "$@"，会静默不启动进程）
  # 同时清掉单下划线变体 SERVER_PORT / SERVER_HOST —— 不同宿主/CI 用不同命名，两者都会被
  # Spring 松散绑定为 server.port，漏清其一即端口冲突。
  unset SERVER__PORT SERVER__HOST SERVER_PORT SERVER_HOST
  JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$TMP_FOR_JVM" \
      MSYS_NO_PATHCONV=1 "$MVN" -pl "$m" spring-boot:run > "logs/$m.dev.log" 2>&1 &
  echo "  ➜ $m 已启动 (pid=$!)  日志: logs/$m.dev.log"
  sleep 1
done

echo "----------------------------------------------------------"
echo "已后台启动 ${#MODULES[@]} 个服务。查看某服务日志：tail -f logs/<module>.dev.log"
echo "网关统一入口：http://localhost:9080   接口文档：http://localhost:9080/doc.html"
echo "判断服务是否就绪：curl -s http://localhost:<port>/actuator/health"
