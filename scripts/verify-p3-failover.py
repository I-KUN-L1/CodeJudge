#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P3 故障转移混沌测试：
模拟一个 worker 判题中途宕机 —— 把某个已完成任务手工置回
「JUDGING + 租约已过期 + 原持有者=dead-worker」的状态（等价于 worker 被杀后
残留的现场），然后观察 judge-submission 的补偿调度是否在 10s 扫描周期内：
  1. CAS 接管租约（attempt+1，复位 PENDING）；
  2. 重投 RETRY 消息；
  3. 在线 worker 重新认领并完成判题（任务回到 SUCCESS）。

用法：python scripts/verify-p3-failover.py
"""

import os
import subprocess
import sys
import time

import requests

GATEWAY = os.environ.get("CJ_P3_GATEWAY", "http://localhost:9080")
MYSQL_CTR = "codejudge-mysql"


def mysql(sql):
    pwd = None
    with open(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".env"),
              encoding="utf-8") as f:
        for line in f:
            if line.startswith("MYSQL_ROOT_PASSWORD="):
                pwd = line.strip().split("=", 1)[1]
    cmd = ["docker", "exec", MYSQL_CTR, "mysql", "-uroot", f"-p{pwd}",
           "--default-character-set=utf8mb4", "-N", "-e", sql]
    out = subprocess.run(cmd, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(out.stderr)
    return out.stdout.strip()


def login(phone, password):
    r = requests.post(f"{GATEWAY}/accounts/login",
                      json={"cellPhone": phone, "password": password}, timeout=10).json()
    return {"Authorization": "Bearer " + r["data"]["accessToken"]}


def main():
    headers = login(os.environ.get("CJ_P3_PHONE", "13900000001"),
                    os.environ.get("CJ_P3_PASS", "123456"))

    # 找一个已完成的提交 + 其任务
    row = mysql("SELECT id, code FROM judge_submission.submission "
                "WHERE status='SUCCESS' AND verdict='AC' AND deleted=0 ORDER BY id DESC LIMIT 1")
    submission_id, _ = row.split("\t")
    task = mysql(f"SELECT id, attempt FROM judge_submission.judge_task "
                 f"WHERE submission_id={submission_id} AND deleted=0 ORDER BY id DESC LIMIT 1")
    task_id, attempt = task.split("\t")
    print(f"选中已完成提交 submissionId={submission_id} taskId={task_id} attempt={attempt}")

    # 混沌注入：模拟 worker 宕机残留现场（JUDGING + 租约已过期）
    mysql(f"UPDATE judge_submission.judge_task SET status='JUDGING', worker_id='dead-worker', "
          f"lease_owner='dead-worker', lease_expire_at=DATE_SUB(NOW(), INTERVAL 5 SECOND), "
          f"attempt=0, error_msg='chaos: worker killed mid-judge' WHERE id={task_id}")
    mysql(f"UPDATE judge_submission.submission SET status='JUDGING' WHERE id={submission_id}")
    print("已注入故障现场：task=JUDGING 租约已过期 原持有者=dead-worker")

    deadline = time.time() + 60
    while time.time() < deadline:
        t = mysql(f"SELECT status, attempt, worker_id, error_msg FROM judge_submission.judge_task WHERE id={task_id}")
        s = mysql(f"SELECT status, verdict FROM judge_submission.submission WHERE id={submission_id}")
        print(f"  轮询：task={t.replace(chr(9), ' | ')}  submission={s.replace(chr(9), ' | ')}")
        parts = t.split("\t")
        if parts[0] == "SUCCESS" and parts[2] != "dead-worker":
            print("\n[PASS] 故障转移闭环：租约过期 → 补偿接管(attempt+1) → RETRY 重投 → 在线 worker 重新判题完成")
            return
        time.sleep(5)
    print("\n[FAIL] 60s 内未观察到接管后重新判题完成")
    sys.exit(1)


if __name__ == "__main__":
    main()
