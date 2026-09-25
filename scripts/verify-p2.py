#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
CodeJudge —— P2 验收脚本（题目域：可见性隔离 + 归属越权）

用法：
    python scripts/verify-p2.py
    BASE=http://localhost:9083 python scripts/verify-p2.py

前置：judge-problem 已启动（默认 9083），且 sql/seed.sql 已灌入。

依赖：仅标准库（urllib）。不需要 requests，也不需要 curl。

────────────────────────────────────────────────────────────
为什么直连 9083 而不是走网关 9080？
  本脚本验证的是**服务层**的归属与可见性逻辑。身份由网关鉴权后以
  user-info / role-info 头注入，直连时手工构造这两个头即可复现网关行为。
  走网关反而多一层耦合（需先拿到真 JWT），失败时难以区分是网关还是服务的问题。
  因此本脚本构造的身份头**只在本地直连场景有意义**。生产必须经网关 ——
    网关会剥离外部传入的这两个头（见 judge-gateway 的 AuthGlobalFilter）。

为什么是 .py 而不是 .sh？
  原实现是 bash + curl。但在部分受管 Windows 环境中，执行 .sh 文件会被路由到
  wsl.exe，而 wsl.exe 可能位于程序黑名单，脚本会被直接中止（实测连一行 echo
  的 .sh 都无法执行）。改为纯标准库的 Python 后不再依赖外部程序，可稳定执行。
  scripts/verify-p2.sh 仍保留为薄封装，供习惯 bash 的环境使用。
────────────────────────────────────────────────────────────
"""

import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("BASE", "http://localhost:9083")

# 角色常量（与 judge-common 的 UserRole 对齐）
ROLE_STAFF = 1
ROLE_STUDENT = 2
ROLE_TEACHER = 3

# 种子账号 id（见 sql/seed.sql）
U_STUDENT = 2001
U_TEACHER_OWNER = 2101   # 4001 / 4002 / 4003 的归属教师
U_TEACHER_OTHER = 2102   # 4004 / 4005 / 4006 的归属教师

PASS = 0
FAIL = 0


def call(method, path, user_id, role, body=None):
    """发起请求并返回解析后的 JSON。服务端业务错误也走 HTTP 200 + body.code，故不按状态码分支。"""
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("user-info", str(user_id))
    req.add_header("role-info", str(role))
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw = resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
    except Exception as e:  # 连接失败等
        return {"__error__": str(e)}
    try:
        return json.loads(raw)
    except Exception:
        return {"__error__": "invalid json: " + raw[:200]}


def dig(doc, dotted):
    """按 a.b.c 取值；缺失或中途为 null 返回 None。"""
    cur = doc
    for part in dotted.split("."):
        if isinstance(cur, dict) and part in cur:
            cur = cur[part]
        else:
            return None
    return cur


def check(label, actual, expected):
    global PASS, FAIL
    if actual == expected:
        print("  [PASS] %-52s %s" % (label, actual))
        PASS += 1
    else:
        print("  [FAIL] %-52s got=%r want=%r" % (label, actual, expected))
        FAIL += 1


def main():
    print("目标：%s" % BASE)
    print("=" * 62)

    # ---------- 1. 学员列表：只见已发布 ----------
    print("[1] 学员列表可见性")
    r = call("GET", "/problems/page?pageSize=50", U_STUDENT, ROLE_STUDENT)
    check("学员列表总数（5 已发布 + 1 草稿）", dig(r, "data.total"), 5)
    lst = dig(r, "data.list") or []
    ids = [x.get("id") for x in lst]
    check("列表不含草稿题 4006", 4006 in ids, False)

    # ---------- 2. 学员详情：隐藏用例不下发 ----------
    print("[2] 学员详情隐藏用例隔离（4001 共 4 用例，其中 2 隐藏）")
    r = call("GET", "/problems/4001", U_STUDENT, ROLE_STUDENT)
    check("samples 条数（可见样例）", len(dig(r, "data.samples") or []), 2)
    check("testCases 为 null（无权限）", dig(r, "data.testCases"), None)
    check("hiddenCaseCount 为 null", dig(r, "data.hiddenCaseCount"), None)

    # ---------- 3. 归属教师详情：全量用例下发 ----------
    print("[3] 归属教师详情（2101 拥有 4001）")
    r = call("GET", "/problems/4001", U_TEACHER_OWNER, ROLE_TEACHER)
    check("testCases 条数（含隐藏）", len(dig(r, "data.testCases") or []), 4)
    check("hiddenCaseCount", dig(r, "data.hiddenCaseCount"), 2)

    # ---------- 4. 未发布题目对非归属者不可见 ----------
    print("[4] 草稿题可见性（4006 归属 2102，status=0）")
    check("学员访问草稿题被拒",
          dig(call("GET", "/problems/4006", U_STUDENT, ROLE_STUDENT), "code"), 403)
    check("非归属教师访问他人草稿被拒",
          dig(call("GET", "/problems/4006", U_TEACHER_OWNER, ROLE_TEACHER), "code"), 403)
    check("归属教师可访问自己的草稿",
          dig(call("GET", "/problems/4006", U_TEACHER_OTHER, ROLE_TEACHER), "code"), 200)

    # ---------- 5. 归属越权（P2 验收关键负例） ----------
    print("[5] 归属越权拦截 - OwnerAccessGuard")
    check("教师改他人题目被拒",
          dig(call("PUT", "/problems/4001", U_TEACHER_OTHER, ROLE_TEACHER,
                   {"title": "越权改名"}), "code"), 403)
    check("学员改题目被拒（角色门槛）",
          dig(call("PUT", "/problems/4001", U_STUDENT, ROLE_STUDENT,
                   {"title": "学员越权改名"}), "code"), 403)
    check("学员建题被拒（角色门槛）",
          dig(call("POST", "/problems", U_STUDENT, ROLE_STUDENT,
                   {"title": "学员建题"}), "code"), 403)

    # ---------- 6. 正向：归属教师改自己的题 ----------
    print("[6] 正向路径")
    check("归属教师改自己的题成功",
          dig(call("PUT", "/problems/4001", U_TEACHER_OWNER, ROLE_TEACHER,
                   {"statement": "P2 验收题面（可重复执行）"}), "code"), 200)
    r = call("GET", "/problems/4001", U_TEACHER_OWNER, ROLE_TEACHER)
    # 断言"已递增"而非"=2"：脚本可重复执行，每跑一次追加一版
    check("改题后版本号已递增（≠1）", dig(r, "data.versionNo") != 1, True)
    check("题面为新版本内容", dig(r, "data.statement"), "P2 验收题面（可重复执行）")

    # ---------- 7. 教师列表范围 ----------
    print("[7] 教师列表范围")
    r = call("GET", "/problems/page?pageSize=50", U_TEACHER_OWNER, ROLE_TEACHER)
    check("教师可见总数（5 已发布 + 自己草稿 0）", dig(r, "data.total"), 5)

    # ---------- 8. 标签 ----------
    print("[8] 标签")
    r = call("GET", "/tags", U_STUDENT, ROLE_STUDENT)
    check("标签条数（种子 10 条）", len(dig(r, "data") or []), 10)
    check("学员建标签被拒",
          dig(call("POST", "/tags", U_STUDENT, ROLE_STUDENT, {"name": "学员越权建标签"}), "code"), 403)

    # ---------- 9. 中文编码 ----------
    print("[9] 中文编码（防双重编码回归）")
    r = call("GET", "/problems/4005", U_STUDENT, ROLE_STUDENT)
    check("题目标题字符长度（最短路上机综合题=8）", len(dig(r, "data.title") or ""), 8)

    print("=" * 62)
    print("通过 %d 项，失败 %d 项" % (PASS, FAIL))
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
