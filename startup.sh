#!/usr/bin/env bash
# ============================================================
# CodeJudge 单服务启动脚本（对齐 zx-learn startup.sh 参数风格）
# 同时也是仓库根综合入口 startup.py 的透传门面：
#   - 带 -c/-n/-d/-p 参数时：启动单个后端服务 jar
#   - 不带参数或带 --all/--with-web 等：原样透传给 python startup.py
#
# 用法（仓库根目录）：
#   ./startup.sh -c judge-auth -n judge-auth -d target/judge-auth.jar -p 9081
#   ./startup.sh                      # = python startup.py（基础设施 + 8 服务）
#   ./startup.sh --all --wait         # 透传综合入口
#   ./startup.sh -h                   # 帮助
#
# 说明：本机 Git Bash 须以 `bash startup.sh ...` 或 `./startup.sh ...` 显式调用。
# ============================================================
set -euo pipefail

usage() {
  cat <<'EOF'
CodeJudge startup.sh —— 单服务启动 / 综合启动透传

单服务模式（四参数齐全才生效）:
  -c  服务模块名，如 judge-auth
  -n  进程/日志名，如 judge-auth
  -d  jar 路径（相对仓库根或绝对路径），如 target/judge-auth.jar
  -p  监听端口，如 9081

综合模式（透传给 python startup.py）:
  ./startup.py 的任意参数（--all / --with-web / --no-infra / --wait ...）

通用:
  -h  显示本帮助
EOF
}

CP=""; NP=""; DP=""; PP=""
while getopts ":hc:n:d:p:" opt; do
  case "$opt" in
    h) usage; exit 0 ;;
    c) CP="$OPTARG" ;;
    n) NP="$OPTARG" ;;
    d) DP="$OPTARG" ;;
    p) PP="$OPTARG" ;;
    \?) echo "未知选项: -$OPTARG" >&2; usage; exit 1 ;;
    :)  echo "选项 -$OPTARG 缺少参数" >&2; usage; exit 1 ;;
  esac
done

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

# ---- 单服务模式：四个参数必须齐全，缺参报错 ----
if [ -n "$CP$NP$DP$PP" ]; then
  missing=0
  for v in CP NP DP PP; do
    if [ -z "${!v}" ]; then echo "错误: -$v 缺少参数" >&2; missing=1; fi
  done
  [ "$missing" -eq 0 ] || { usage; exit 1; }
  [ -f "$DP" ] || { echo "错误: jar 不存在: $DP" >&2; exit 1; }

  # 宿主会向子 JVM 注入 SERVER__PORT/SERVER__HOST（见 docs/CONTEXT.md §3.13），
  # 必须 unset，否则 Spring 松散绑定会覆盖 --server.port
  unset SERVER__PORT SERVER__HOST SERVER_PORT SERVER_HOST 2>/dev/null || true
  echo "[startup.sh] 启动 $NP (port=$PP, jar=$DP)"
  exec java -jar "$DP" --server.port="$PP"
fi

# ---- 综合模式：透传 python startup.py ----
PYTHON="${PYTHON:-python}"
if ! command -v "$PYTHON" >/dev/null 2>&1; then
  echo "错误: 未找到 python（可用 PYTHON=<绝对路径> 覆盖）" >&2
  exit 1
fi
exec "$PYTHON" "$REPO_ROOT/startup.py" "$@"
