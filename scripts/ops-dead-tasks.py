#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""死信判题任务运维工具 —— 上线前清理与日常运维。

用法：
    python scripts/ops-dead-tasks.py list          # 列出「未处理」的死信（deleted=0）
    python scripts/ops-dead-tasks.py list --all    # 连已软删的一起列
    python scripts/ops-dead-tasks.py purge         # 软删未处理的死信（可 restore 回滚）
    python scripts/ops-dead-tasks.py restore       # 恢复全部被软删的死信

为什么走 `docker exec ... sh -c 'mysql -p"$MYSQL_ROOT_PASSWORD"'`：
    数据库口令从**容器内**的环境变量取，不经过本脚本、不落日志、不进 shell 历史。
    这是本项目处理数据库凭据的既定方式（对比 `scripts/rotate-credentials.py` 说明）。

⚠ `purge` 是**软删**（`deleted=1`）而不是 DELETE：
    · 记录仍在表里，`restore` 可一键回滚；
    · `judge_dead_tasks` 指标由 MyBatis-Plus 逻辑删除自动过滤（`logic-delete-field: deleted`），
      所以 purge 后指标立即归零，`JudgeDeadTasksPresent` 这条 critical 告警随之熄灭；
    · 死信本身**不会自动重试**（attempt 已耗尽），删除不改变判题语义 ——
      对应 submission 早已是终态（通常 `FAILED` / `SE`）。
"""
from __future__ import annotations

import argparse
import shlex
import subprocess
import sys

CONTAINER = "codejudge-mysql"
DB = "judge_submission"


def mysql(sql: str, *, batch: bool = True) -> tuple[int, str]:
    """在 MySQL 容器内执行 SQL。返回 (退出码, 输出)。"""
    flags = "-B" if batch else ""
    inner = (f'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" {flags} -D {DB} '
             f'--default-character-set=utf8mb4 -e {shlex.quote(sql)}')
    try:
        p = subprocess.run(["docker", "exec", CONTAINER, "sh", "-c", inner],
                           capture_output=True, text=True,
                           encoding="utf-8", errors="replace", timeout=60)
    except FileNotFoundError:
        return 127, "找不到 docker 命令"
    except subprocess.TimeoutExpired:
        return 124, "执行超时"
    # mysql 会把「Using a password on the command line」和挂载点权限警告写到 stderr，
    # 二者都是正常噪音 —— 混进 stdout 会让按行计数出错（曾把 2 条显示成 3 条）。
    NOISE = ("Using a password", "World-writable config file")
    err = "\n".join(l for l in (p.stderr or "").splitlines()
                    if l.strip() and not any(n in l for n in NOISE))
    out = (p.stdout or "").strip()
    if err:
        out = f"{out}\n[stderr] {err}".strip()
    return p.returncode, out


def container_ok() -> bool:
    p = subprocess.run(["docker", "inspect", "-f", "{{.State.Running}}", CONTAINER],
                       capture_output=True, text=True, encoding="utf-8",
                       errors="replace")
    return (p.stdout or "").strip() == "true"


def show(sql_where: str, title: str) -> None:
    sql = (
        "SELECT t.id, t.submission_id, t.attempt, t.max_attempt, t.deleted, "
        "LEFT(REPLACE(REPLACE(IFNULL(t.error_msg,''), CHAR(10), ' '), CHAR(13), ' '), 60) AS err, "
        "t.create_time, s.status AS sub_status, s.verdict "
        f"FROM judge_task t LEFT JOIN submission s ON s.id = t.submission_id {sql_where} "
        "ORDER BY t.id"
    )
    rc, out = mysql(sql)
    print(f"\n=== {title} ===")
    if rc != 0:
        print(f"[ERROR] 查询失败（rc={rc}）：{out}")
        return
    lines = out.splitlines()
    if len(lines) <= 1:
        print("（无记录）")
        return
    hdr = lines[0].split("\t")
    print(" | ".join(hdr))
    print("-" * 96)
    for line in lines[1:]:
        cols = line.split("\t")
        print(" | ".join(cols))
    print(f"\n共 {len(lines) - 1} 条。")


def main() -> int:
    ap = argparse.ArgumentParser(description="死信判题任务运维工具")
    ap.add_argument("action", choices=["list", "purge", "restore"])
    ap.add_argument("--all", action="store_true",
                    help="list 时连已软删的一起显示")
    args = ap.parse_args()

    if not container_ok():
        print(f"[FATAL] 容器 {CONTAINER} 未运行 —— 先执行 docker-compose up -d", file=sys.stderr)
        return 2

    if args.action == "list":
        where = "" if args.all else "WHERE t.status='DEAD' AND t.deleted=0"
        if args.all:
            where = "WHERE t.status='DEAD'"
        show(where, f"DEAD 任务（{'含已软删' if args.all else '未处理'}）")

        # 顺带给出指标口径，避免「库里 2 条但指标 0」这种困惑
        rc, out = mysql("SELECT COUNT(*) FROM judge_task WHERE status='DEAD' AND deleted=0")
        if rc == 0 and len(out.splitlines()) > 1:
            n = out.splitlines()[1].strip()
            print(f"judge_dead_tasks 指标口径（status='DEAD' AND deleted=0）= {n}")
        return 0

    if args.action == "purge":
        rc, out = mysql("SELECT COUNT(*) FROM judge_task WHERE status='DEAD' AND deleted=0")
        if rc != 0:
            print(f"[ERROR] {out}", file=sys.stderr)
            return 2
        n = out.splitlines()[1].strip() if len(out.splitlines()) > 1 else "0"
        if n == "0":
            print("没有未处理的死信，无需清理。")
            return 0
        print(f"将软删 {n} 条死信任务（deleted 0 → 1，可 restore 回滚）。")
        rc, out = mysql(
            "UPDATE judge_task SET deleted=1, update_time=NOW() "
            "WHERE status='DEAD' AND deleted=0; SELECT ROW_COUNT() AS affected;")
        print(out)
        if rc == 0:
            print("✅ 完成。judge_dead_tasks 会在下次 scrape 时归零。")
            print("   回滚：python scripts/ops-dead-tasks.py restore")
        return rc

    # restore
    rc, out = mysql(
        "UPDATE judge_task SET deleted=0, update_time=NOW() "
        "WHERE status='DEAD' AND deleted=1; SELECT ROW_COUNT() AS affected;")
    print(out)
    if rc == 0:
        print("✅ 已恢复。")
    return rc


if __name__ == "__main__":
    sys.exit(main())
