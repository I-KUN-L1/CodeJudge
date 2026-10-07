#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify-demo-accounts.py —— 系统功能演示账户端到端验证（含验证后自动恢复初始状态）。

────────────────────────────────────────────────────────────────────────────────
验证内容（对应「演示初始账户」需求的四个验收点）
────────────────────────────────────────────────────────────────────────────────
  ① 演示学生（13900000098/studentdemo）：
     - 登录成功，角色为学员；
     - 学员视角可见题目 = 全部已发布题（不含 4006/4021/4022 等草稿题）；
     - 每道已发布题都有"答案已填好但尚未提交"的草稿（GET /submissions/drafts/{id}）；
     - 抽取 4001 实际走一遍提交 → 判题 → 结论应为 AC（完整真实判题链路）；
     - 提交成功后该题草稿被消费（GET 返回 null）。
  ② 演示教师（13900000099/teacherdemo）：
     - 登录成功，角色为教师；
     - 名下草稿题 4021/4022 可见且 status=0（已编辑完成、待发布）；
     - 演示竞赛 5003 可见且 status=0（未开始）。
  ③ 凭据文件：.bootstrap-credentials 存在且包含两个演示账号区段。
  ④ 恢复：验证完成后默认调用 scripts/restore-demo-state.py 把演示数据拉回初始状态
     （学生草稿重灌、教师题目拉回未发布、演示提交物理删除）。--keep 可跳过。

────────────────────────────────────────────────────────────────────────────────
用法（仓库根目录，需栈已起：scripts/start-all.py）
────────────────────────────────────────────────────────────────────────────────
    python scripts/verify-demo-accounts.py            # 验证 + 自动恢复
    python scripts/verify-demo-accounts.py --keep     # 只验证，不恢复
    python scripts/verify-demo-accounts.py --student-password ... --teacher-password ...
"""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV_PATH = os.path.join(ROOT, ".env")
GATEWAY = "http://127.0.0.1:9080"
# Git Bash 下 curl/urllib 会走宿主代理，127.0.0.1 必须显式绕过
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

DEMO_STUDENT_PHONE = "13900000098"
DEMO_TEACHER_PHONE = "13900000099"
# 已发布题目 id（= 演示学生应持有草稿的题目全集；不含草稿题 4006/4021/4022）
PUBLISHED_PROBLEM_IDS = [4001, 4002, 4003, 4004, 4005, 4007, 4008, 4009, 4010, 4011, 4012]
DRAFT_PROBLEM_IDS = [4006, 4021, 4022]
DEMO_CONTEST_ID = 5003

PASS, FAIL = [], []


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


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    log(f"  {'✓' if cond else '★'} {name}" + (f"（{detail}）" if detail and not cond else ""))


def call(method, path, body=None, token=None, timeout=15):
    req = urllib.request.Request(GATEWAY + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with OPENER.open(req, data, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw or "{}")
        except Exception:
            return e.code, {"raw": raw[:200]}
    except Exception as e:
        return 0, {"raw": f"{type(e).__name__}: {e}"}


def login(phone, password, tries=8):
    """登录；网关登录令牌桶 2 req/s（突发 5），空响应体 = 被限流。"""
    for _ in range(tries):
        st, body = call("POST", "/accounts/login", {"cellPhone": phone, "password": password})
        if st == 0:
            return None, None
        if body.get("data"):
            return body["data"], body["data"].get("accessToken")
        if body.get("code") not in (None, 429) and body.get("msg"):
            return None, None
        time.sleep(0.9)
    return None, None


def main():
    ap = argparse.ArgumentParser(description="演示账户端到端验证 + 自动恢复")
    ap.add_argument("--student-password", default=env_value("CJ_DEMO_INIT_PASSWORD", "123456"))
    ap.add_argument("--teacher-password", default=env_value("CJ_DEMO_INIT_PASSWORD", "123456"))
    ap.add_argument("--keep", action="store_true", help="验证后不调用 restore-demo-state.py")
    args = ap.parse_args()

    cred_rel = env_value("CJ_ADMIN_CREDENTIAL_FILE", ".bootstrap-credentials")
    cred_path = cred_rel if os.path.isabs(cred_rel) else os.path.join(ROOT, cred_rel)

    log("=" * 78)
    log("演示账户端到端验证")
    log("=" * 78)

    st, body = call("GET", "/accounts/login")
    if st == 0:
        log("★ 网关 127.0.0.1:9080 不可达 —— 请先 scripts/start-all.py 起栈。")
        sys.exit(1)

    # ---- ① 演示学生 ----
    log("\n[1] 演示学生 " + DEMO_STUDENT_PHONE)
    s, token = login(DEMO_STUDENT_PHONE, args.student_password)
    check("演示学生可登录", bool(token))
    if not token:
        log("    提示：演示账号口令与其余测试账号同规（见 sql/seed.sql 头注）；"
            "若已改密，用 --student-password 传入新口令。")
        sys.exit(1)
    check("登录未误报 mustChangePassword", s.get("mustChangePassword") is not True,
          "学员不是 STAFF，不应吃到引导态提示")

    st, body = call("GET", "/problems/page?pageNo=1&pageSize=50", token=token)
    ids = {int(p["id"]) for p in (body.get("data") or {}).get("list", []) if str(p.get("id", "")).isdigit()}
    check(f"学员可见题目数 = {len(PUBLISHED_PROBLEM_IDS)}", ids == set(PUBLISHED_PROBLEM_IDS),
          f"实际可见：{sorted(ids)}")
    check("草稿题对学员不可见", not (ids & set(DRAFT_PROBLEM_IDS)))

    drafts_ok = 0
    for pid in PUBLISHED_PROBLEM_IDS:
        st, body = call("GET", f"/submissions/drafts/{pid}", token=token)
        d = (body.get("data") or {})
        if st == 200 and d.get("code"):
            drafts_ok += 1
        else:
            log(f"    题目 {pid} 草稿缺失：HTTP {st} code={body.get('code')}")
    check(f"待提交草稿覆盖 {drafts_ok}/{len(PUBLISHED_PROBLEM_IDS)} 道已发布题",
          drafts_ok == len(PUBLISHED_PROBLEM_IDS))

    # 完整判题链路：提交 4001 草稿代码 → 期望 AC
    st, body = call("GET", "/submissions/drafts/4001", token=token)
    code4001 = (body.get("data") or {}).get("code")
    st, body = call("POST", "/submissions",
                    {"problemId": 4001, "language": "JAVA", "code": code4001}, token=token)
    sub_id = (body.get("data") or {}).get("id")
    check("演示提交受理成功", bool(sub_id))
    if sub_id:
        verdict = None
        for _ in range(30):
            time.sleep(1.0)
            st, body = call("GET", f"/submissions/{sub_id}", token=token)
            d = body.get("data") or {}
            if d.get("status") in ("SUCCESS", "FAILED"):
                verdict = d.get("verdict")
                break
        check("演示提交判题结论 = AC", verdict == "AC", f"实际 verdict={verdict}")
        st, body = call("GET", "/submissions/drafts/4001", token=token)
        check("提交成功后草稿已消费", (body.get("data") or {}).get("code") in (None, ""))

    # ---- ② 演示教师 ----
    log("\n[2] 演示教师 " + DEMO_TEACHER_PHONE)
    t, ttoken = login(DEMO_TEACHER_PHONE, args.teacher_password)
    check("演示教师可登录", bool(ttoken))
    if ttoken:
        for pid, vid in ((4021, None), (4022, None)):
            st, body = call("GET", f"/problems/{pid}", token=ttoken)
            d = body.get("data") or {}
            check(f"草稿题 {pid} 对归属教师可见且 status=0", st == 200 and d.get("status") == 0,
                  f"HTTP={st} status={d.get('status')}")
        st, body = call("GET", f"/contests/{DEMO_CONTEST_ID}", token=ttoken)
        d = body.get("data") or {}
        check(f"演示竞赛 {DEMO_CONTEST_ID} 可见且未开始", st == 200 and d.get("status") == 0,
              f"HTTP={st} status={d.get('status')}")

    # ---- ③ 凭据文件 ----
    log("\n[3] 凭据文件")
    if os.path.exists(cred_path):
        content = open(cred_path, encoding="utf-8", errors="replace").read()
        check("凭据文件存在", True)
        check("含演示学生区段", DEMO_STUDENT_PHONE in content and "studentdemo" in content)
        check("含演示教师区段", DEMO_TEACHER_PHONE in content and "teacherdemo" in content)
    else:
        check("凭据文件存在", False,
              f"{cred_path} 不存在 —— 全新部署首次启动自动生成，或用 "
              f"generate-bootstrap-credentials.py --yes --force 重建")

    # ---- ④ 恢复初始状态 ----
    log("\n[4] 恢复演示数据初始状态")
    if args.keep:
        log("  · --keep：跳过自动恢复。注意：演示学生的 4001 提交与已消费草稿仍为测试后状态，")
        log("    需要时手工执行 python scripts/restore-demo-state.py --yes")
    else:
        r = subprocess.run([sys.executable, os.path.join(ROOT, "scripts", "restore-demo-state.py"), "--yes"])
        check("restore-demo-state.py 执行成功", r.returncode == 0, f"退出码 {r.returncode}")

    log("")
    log("=" * 78)
    log(f"结果：通过 {len(PASS)} / 失败 {len(FAIL)}")
    for f in FAIL:
        log("  ★ FAIL: " + f)
    log("=" * 78)
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
