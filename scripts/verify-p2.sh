#!/usr/bin/env bash
# ==========================================================
# CodeJudge —— P2 验收脚本（薄封装）
# ----------------------------------------------------------
# 真正的实现是 scripts/verify-p2.py（纯标准库，不依赖 curl）。
# 拆成两份是因为：部分受管 Windows 环境执行 .sh 会被路由到 wsl.exe，
# 而 wsl.exe 可能位于程序黑名单 → 整个脚本被中止。
# 保留本封装只是为了让习惯 bash 的环境有一条一致的入口。
#
# 用法：
#   bash scripts/verify-p2.sh
#   BASE=http://localhost:9083 bash scripts/verify-p2.sh
#   PY=/path/to/python.exe bash scripts/verify-p2.sh
# ==========================================================
set -u

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 解释器探测：PY 显式指定 > python.exe > python3 > python。
# 把 python.exe 排在前面：Windows + Git Bash 下 python3/python 常是应用商店
# 的转发 shim，会去拉起 wsl.exe（在受管环境里可能直接被黑名单拦截）。
if [ -z "${PY:-}" ]; then
  for cand in python.exe python3 python; do
    if command -v "$cand" >/dev/null 2>&1; then
      PY="$cand"
      break
    fi
  done
fi
if [ -z "$PY" ]; then
  echo "✗ 未找到 python 解释器。请安装，或用 PY=/path/to/python.exe 指定。"
  exit 2
fi

exec "$PY" "$DIR/verify-p2.py" "$@"
