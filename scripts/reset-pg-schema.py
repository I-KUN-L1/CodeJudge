# -*- coding: utf-8 -*-
"""
I1: PG 向量库 schema 自检与自愈。
- 默认（自检）：核对 ai_review.embedding / knowledge_chunk.embedding 列是否存在；
- --fix（自愈）：重放 deploy/pgvector/init.sql（全程 IF NOT EXISTS / ADD COLUMN IF NOT EXISTS，幂等）。

用法（仓库根）:
    python scripts/reset-pg-schema.py          # 自检
    python scripts/reset-pg-schema.py --fix    # 自检 + 缺列时自愈
退出码：0=schema 完整；1=缺列且未修复（verify-p5 前置调用可据此拦下）。
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INIT_SQL = ROOT / "deploy" / "pgvector" / "init.sql"

CHECK_SQL = (
    "SELECT table_name || '.' || column_name FROM information_schema.columns "
    "WHERE table_schema='public' AND table_name IN ('ai_review','knowledge_chunk') "
    "AND column_name='embedding';"
)
EXPECTED = {"ai_review.embedding", "knowledge_chunk.embedding"}


def psql(sql, label):
    r = subprocess.run(
        ["docker", "exec", "-i", "codejudge-pg", "psql", "-U", "postgres",
         "-d", "judge_ai", "-v", "ON_ERROR_STOP=1", "-t", "-A", "-c", sql],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        print(f"[FAIL] {label}：{r.stderr.strip()[:500]}", file=sys.stderr)
        print("提示：PG 容器未运行时先 `docker compose up -d postgres`。", file=sys.stderr)
        sys.exit(2)
    return r.stdout


def main():
    fix = "--fix" in sys.argv
    out = psql(CHECK_SQL, "schema 自检")
    found = {line.strip() for line in out.splitlines() if line.strip()}
    missing = EXPECTED - found
    print(f"自检：embedding 列 {sorted(found)}；缺失：{sorted(missing) if missing else '无'}")

    if not missing:
        print("OK schema 完整")
        return 0
    if not fix:
        print("缺列。修复：python scripts/reset-pg-schema.py --fix")
        return 1

    sql = INIT_SQL.read_text(encoding="utf-8")
    psql(sql, "自愈（重放 deploy/pgvector/init.sql）")
    out2 = psql(CHECK_SQL, "修复后复检")
    still = EXPECTED - {l.strip() for l in out2.splitlines() if l.strip()}
    if still:
        print(f"[FAIL] 修复后仍缺：{sorted(still)}", file=sys.stderr)
        return 1
    print("OK 自愈完成，schema 完整")
    return 0


if __name__ == "__main__":
    sys.exit(main())
