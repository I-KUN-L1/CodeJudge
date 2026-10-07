#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把系统功能演示账户的数据恢复到初始状态。

────────────────────────────────────────────────────────────────────────────────
恢复范围（只动演示专用数据，不碰普通测试账户）
────────────────────────────────────────────────────────────────────────────────
  · 演示学生（user 2999 / 13900000098）：
      - 物理删除其全部提交记录及下游（judge_task / judge_result / compile_info）；
        必须物理删 —— 逻辑删除行仍占用 uk_submission_idempotent 幂等键，
        演示学生再提交相同答案会被幂等去重挡住；
      - 清空并重灌其 code_draft（标准答案待提交状态）；
  · 演示教师（user 2110 / 13900000099）：
      - 草稿题 4021/4022 拉回 status=0（未发布）；若发布后被编辑过，删除 version_no>1
        的题面版本并复位 current_version_id；
  · 演示竞赛（contest 5003）：
      - 删除竞赛/编排/报名/榜单快照后由 sql/seed.sql 重放，时间窗口以执行时刻为基准
        重新推到未来（status=0 未开始）；
  · 计数修正：problem.submit_count/accepted_count、contest_problem 计数按现存
    （未逻辑删除的）提交全量重算；
  · 凭据文件：若 .bootstrap-credentials 不存在，提示用
    scripts/generate-bootstrap-credentials.py --yes --force 重建（脚本不代写凭据）。

────────────────────────────────────────────────────────────────────────────────
用法（仓库根目录）
────────────────────────────────────────────────────────────────────────────────
    python scripts/restore-demo-state.py             # 预演：只打印将执行的 SQL
    python scripts/restore-demo-state.py --yes       # 真正执行
    python scripts/restore-demo-state.py --yes --container my-mysql
"""

import argparse
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV_PATH = os.path.join(ROOT, ".env")

DEMO_STUDENT_ID = "2999"
DEMO_TEACHER_ID = "2110"
DEMO_PROBLEM_IDS = "(4021, 4022)"
DEMO_CONTEST_ID = "5003"
DEMO_DRAFT_PROBLEM_VERSION = {"4021": "4121", "4022": "4122"}


def log(msg=""):
    print(msg, flush=True)


def env_value(key, default=""):
    try:
        with open(ENV_PATH, "r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                if k.strip() == key:
                    return v.strip().strip('"').strip("'")
    except FileNotFoundError:
        pass
    return default


# 每条语句一个元素；多语句会包进一次 mysql 调用。USE 语句切换库。
RESTORE_SQL = [
    # ---- 1. 演示学生的提交（物理删，连带判题下游） ----
    f"""USE `judge_submission`;
DELETE FROM compile_info  WHERE submission_id IN (SELECT id FROM (SELECT id FROM submission WHERE user_id = {DEMO_STUDENT_ID}) t);
DELETE FROM judge_result  WHERE submission_id IN (SELECT id FROM (SELECT id FROM submission WHERE user_id = {DEMO_STUDENT_ID}) t);
DELETE FROM judge_task    WHERE submission_id IN (SELECT id FROM (SELECT id FROM submission WHERE user_id = {DEMO_STUDENT_ID}) t);
DELETE FROM submission    WHERE user_id = {DEMO_STUDENT_ID};""",
    # ---- 2. 演示学生草稿：清空（seed.sql 重放重灌） ----
    f"USE `judge_submission`;\nDELETE FROM code_draft WHERE user_id = {DEMO_STUDENT_ID};",
    # ---- 3. 演示竞赛：删除后由 seed.sql 重放（时间窗口刷新为"未来"） ----
    f"""USE `judge_contest`;
DELETE FROM contest_rank_snapshot WHERE contest_id = {DEMO_CONTEST_ID};
DELETE FROM contest_registration  WHERE contest_id = {DEMO_CONTEST_ID};
DELETE FROM contest_problem       WHERE contest_id = {DEMO_CONTEST_ID};
DELETE FROM contest               WHERE id = {DEMO_CONTEST_ID};""",
    # ---- 4. 演示教师草稿题：拉回未发布；编辑产生的 v2+ 题面版本一并清除 ----
    f"""USE `judge_problem`;
DELETE FROM problem_version WHERE problem_id IN {DEMO_PROBLEM_IDS} AND version_no > 1;
UPDATE problem SET status = 0, update_time = NOW() WHERE id IN {DEMO_PROBLEM_IDS} AND owner_id = {DEMO_TEACHER_ID};""",
    # current_version_id 复位到 v1（仅当它指向被删掉的 v2+ 时才需要；这里直接按 id 段复位）
    f"""USE `judge_problem`;
UPDATE problem SET current_version_id = 4121 WHERE id = 4021;
UPDATE problem SET current_version_id = 4122 WHERE id = 4022;""",
    # ---- 5. 计数全量重算（只统计未逻辑删除的提交） ----
    f"""USE `judge_problem`;
UPDATE problem p SET
  p.submit_count   = (SELECT COUNT(*) FROM judge_submission.submission s
                      WHERE s.problem_id = p.id AND s.deleted = 0),
  p.accepted_count = (SELECT COUNT(*) FROM judge_submission.submission s
                      WHERE s.problem_id = p.id AND s.deleted = 0 AND s.verdict = 'AC');""",
    f"""USE `judge_contest`;
UPDATE contest_problem cp SET
  cp.submit_count   = (SELECT COUNT(*) FROM judge_submission.submission s
                       WHERE s.contest_id = cp.contest_id AND s.problem_id = cp.problem_id AND s.deleted = 0),
  cp.accepted_count = (SELECT COUNT(*) FROM judge_submission.submission s
                       WHERE s.contest_id = cp.contest_id AND s.problem_id = cp.problem_id AND s.deleted = 0 AND s.verdict = 'AC');""",
]


def run_mysql(sql, container, root_password):
    cmd = ["docker", "exec", "-i", container, "mysql", "-uroot", f"-p{root_password}",
           "--default-character-set=utf8mb4"]
    proc = subprocess.run(cmd, input=sql.encode("utf-8"), capture_output=True)
    if proc.returncode != 0:
        log("  ★ MySQL 执行失败：")
        log("    " + proc.stderr.decode("utf-8", "replace").strip()[:500])
        return False
    return True


def main():
    ap = argparse.ArgumentParser(description="恢复演示账户初始状态（默认预演）")
    ap.add_argument("--yes", action="store_true", help="真正执行（默认只预演）")
    ap.add_argument("--container", default="codejudge-mysql", help="MySQL 容器名")
    args = ap.parse_args()

    root_password = env_value("MYSQL_ROOT_PASSWORD")
    cred_rel = env_value("CJ_ADMIN_CREDENTIAL_FILE", ".bootstrap-credentials")
    cred_path = cred_rel if os.path.isabs(cred_rel) else os.path.join(ROOT, cred_rel)

    log("=" * 78)
    log("恢复演示账户初始状态　模式：" + ("执行" if args.yes else "预演"))
    log("=" * 78)
    log(f"MySQL 容器          : {args.container}（root 口令{'已配置' if root_password else '★ 未配置（MYSQL_ROOT_PASSWORD）'}）")
    log(f"演示学生 / 教师      : user {DEMO_STUDENT_ID} / {DEMO_TEACHER_ID}")
    log(f"演示草稿题 / 竞赛    : {DEMO_PROBLEM_IDS} / {DEMO_CONTEST_ID}")
    log(f"凭据文件            : {cred_path}（{'存在' if os.path.exists(cred_path) else '不存在'}）")
    if not root_password:
        log("\n★ .env 缺 MYSQL_ROOT_PASSWORD，无法连接 MySQL。")
        sys.exit(1)

    log(f"\n将执行 {len(RESTORE_SQL)} 组 SQL：")
    for i, sql in enumerate(RESTORE_SQL, 1):
        first = next(l for l in sql.splitlines() if l.strip() and not l.startswith("USE"))
        log(f"  [{i}/{len(RESTORE_SQL)}] {first.strip()[:70]}")

    if not args.yes:
        log("\n预演结束，未改动任何东西。真正执行请加 --yes")
        log("=" * 78)
        sys.exit(0)

    log("")
    for i, sql in enumerate(RESTORE_SQL, 1):
        ok = run_mysql(sql, args.container, root_password)
        log(f"  [{i}/{len(RESTORE_SQL)}] {'✓' if ok else '★ 失败'}")
        if not ok:
            sys.exit(1)

    # ---- 重放 seed.sql：重灌演示学生草稿 + 演示竞赛（含刷新后的时间窗口） ----
    seed_path = os.path.join(ROOT, "sql", "seed.sql")
    with open(seed_path, "rb") as fh:
        ok = run_mysql(fh.read().decode("utf-8"), args.container, root_password)
    log(f"  [{len(RESTORE_SQL) + 1}/{len(RESTORE_SQL) + 1}] 重放 sql/seed.sql（INSERT IGNORE 幂等）{'✓' if ok else '★ 失败'}")
    if not ok:
        sys.exit(1)

    # ---- 凭据文件 ----
    if not os.path.exists(cred_path):
        log("")
        log(f"★ 凭据文件 {cred_path} 当前不存在。若需恢复演示凭据展示，请执行：")
        log("    python scripts/generate-bootstrap-credentials.py --yes --force")

    log("")
    log("=" * 78)
    log("恢复完成。核对项：")
    log(f"  · 演示学生 {DEMO_STUDENT_ID}：0 条提交记录；11 份 code_draft 待提交草稿")
    log(f"  · 演示教师 {DEMO_TEACHER_ID}：题目 4021/4022 status=0（未发布）")
    log(f"  · 竞赛 {DEMO_CONTEST_ID}：status=0 未开始，时间窗口以本次执行时刻为基准")
    log("  · Redis 实时榜（如有演示学生提交进过 5002 榜单）不会被本脚本清理，")
    log("    需要时可调 POST /contests/5002/rank/rebuild 重建。")
    log("=" * 78)


if __name__ == "__main__":
    main()
