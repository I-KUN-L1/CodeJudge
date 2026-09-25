#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
CodeJudge —— 统一登录 + 能力码（按钮级权限）验收脚本

本脚本验收的是一组**契约**，而不是某个阶段的产物：

  A. 登录只有一个入口：`POST /accounts/login`
     角色由账号自身属性（user.type）决定，调用方不能指定 —— 旧端点
     `/accounts/admin/login` 仅作兼容别名保留，且不得退化成独立逻辑。
  B. 鉴权信息全部来自后端：`GET /accounts/me/capabilities` 下发能力码 + 菜单 + 落地路由，
     前端不做任何"角色 → 能做什么"的推导（见 §5 的静态断言）。
  C. 能力集层次正确：学员 ⊂ 教师 ⊂ 员工，且学员拿不到任何管理面能力码。
  D. 登录页不再外显默认账密，改由 `.bootstrap-credentials` 承载初始凭据。

用法：
    python scripts/verify-authz.py
    GW=http://localhost:9080 python scripts/verify-authz.py
    SKIP_FRONTEND=1 python scripts/verify-authz.py      # 只验后端契约

前置：
    judge-gateway(9080) / judge-auth(9081) / judge-user(9082) 已启动，
    sql/seed.sql 已灌入。前端断言需要 judge-web/dist 存在（否则显式 SKIP，不假绿）。

依赖：仅标准库。不需要 requests，也不需要 curl（Git Bash 下 curl 打 127.0.0.1 需 --noproxy）。

────────────────────────────────────────────────────────────
凭据来源（不硬编码敏感值）：
    学员 13900000001 / 教师 13900000011 的种子口令默认 123456（见 sql/seed.sql）；
    管理员口令取自 .env 的 CJ_ADMIN_INIT_PASSWORD（2026-09-22 已轮换，不再是 123456），
    读取顺序：PWD_ADMIN 环境变量 > .env > 历史默认 123456。

副作用：
    只读。本脚本不创建、不修改、不删除任何账号与文件。
────────────────────────────────────────────────────────────
"""

import json
import os
import pathlib
import re
import sys
import urllib.error
import urllib.request

GW = os.environ.get("GW", "http://localhost:9080")

PHONE_STUDENT = os.environ.get("PHONE_STUDENT", "13900000001")
PHONE_TEACHER = os.environ.get("PHONE_TEACHER", "13900000011")
PHONE_ADMIN = os.environ.get("PHONE_ADMIN", "13800000000")
PWD_SEED = os.environ.get("PWD_SEED", "123456")

# ---- 期望的能力码（与 judge-auth 的 Capabilities / CapabilityService 对齐）----
# 这里刻意把期望值写死一份：若后端悄悄改了映射，本脚本必须红 ——
# 否则"能力码改了但没人知道"会直接表现为线上按钮错乱。
STUDENT_PERMS = {
    "problem:view",
    "contest:view", "contest:register",
    "submission:create", "submission:view-own",
    "ai:review",
}
TEACHER_EXTRA = {
    "problem:create", "problem:edit", "problem:manage", "problem:testcase",
    "contest:create", "contest:manage",
    "submission:view-all", "submission:rejudge", "submission:hidden-output",
    "knowledge:manage",
}
STAFF_EXTRA = {"user:manage", "tag:manage", "worker:view", "monitor:view"}

EXPECTED_STUDENT = STUDENT_PERMS
EXPECTED_TEACHER = STUDENT_PERMS | TEACHER_EXTRA
EXPECTED_STAFF = STUDENT_PERMS | TEACHER_EXTRA | STAFF_EXTRA

EXPECTED_HOME = {"student": "/problems", "teacher": "/teacher/problems", "admin": "/admin/users"}

# 学员绝不该拿到的码（反向断言：防止"给满"式的 fail-open）
FORBIDDEN_FOR_STUDENT = sorted(TEACHER_EXTRA | STAFF_EXTRA)

PASS = 0
FAIL = 0
SKIP = 0


def _env_file_value(key):
    """从仓库根 .env 读取（不回显、不硬编码）。"""
    try:
        env_path = pathlib.Path(__file__).resolve().parent.parent / ".env"
        for line in env_path.read_text(encoding="utf-8", errors="replace").splitlines():
            s = line.strip()
            if s.startswith("#") or "=" not in s:
                continue
            k, v = s.split("=", 1)
            if k.strip() == key:
                return v.strip().strip('"').strip("'")
    except OSError:
        pass
    return ""


PWD_ADMIN = os.environ.get("PWD_ADMIN") or _env_file_value("CJ_ADMIN_INIT_PASSWORD") or "123456"


# ==================== HTTP 基础设施 ====================

def request(method, url, body=None, token=None):
    """
    极简请求。**显式禁用代理**：Git Bash 环境下系统代理变量会让 127.0.0.1 请求
    绕道代理并失败（curl 需要 --noproxy '*'，urllib 同样需要清掉 ProxyHandler）。
    """
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=15) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, _parse(raw)
    except urllib.error.HTTPError as e:
        return e.code, _parse(e.read().decode("utf-8", "replace"))
    except Exception as e:
        return -1, {"__error__": str(e)}


def _parse(raw):
    try:
        return json.loads(raw)
    except Exception:
        return {"__raw__": (raw or "")[:300]}


def dig(doc, dotted, default=None):
    cur = doc
    for part in dotted.split("."):
        if isinstance(cur, dict) and part in cur:
            cur = cur[part]
        else:
            return default
    return cur


def check(label, actual, expected):
    global PASS, FAIL
    if actual == expected:
        print("  [PASS] %-56s %s" % (label, actual))
        PASS += 1
    else:
        print("  [FAIL] %-56s got=%r want=%r" % (label, actual, expected))
        FAIL += 1


def check_true(label, cond, detail=""):
    check(label + ("" if not detail else " (%s)" % detail), bool(cond), True)


def skip(label, why):
    global SKIP
    print("  [SKIP] %-56s %s" % (label, why))
    SKIP += 1


def login(phone, password):
    """返回 (http_status, body)。"""
    return request("POST", GW + "/accounts/login", {"cellPhone": phone, "password": password})


def caps_of(token):
    st, body = request("GET", GW + "/accounts/me/capabilities", token=token)
    return st, body


def perm_codes(body):
    perms = dig(body, "data.perms", []) or []
    return {p.get("code") for p in perms}


def menu_keys(body):
    return [m.get("key") for m in (dig(body, "data.menus", []) or [])]


# ==================== 用例 ====================

def section_1_unified_login():
    print("[1] 统一登录入口：角色由账号自身属性决定")
    tokens = {}
    for label, phone, pwd, want_role, want_alias in (
            ("学员", PHONE_STUDENT, PWD_SEED, 2, "student"),
            ("教师", PHONE_TEACHER, PWD_SEED, 3, "teacher"),
            ("员工", PHONE_ADMIN, PWD_ADMIN, 1, "admin")):
        st, body = login(phone, pwd)
        token = dig(body, "data.accessToken") or ""
        check("%s 登录成功" % label, dig(body, "code"), 200)
        check("%s role 取自 user.type" % label, dig(body, "data.role"), want_role)
        check("%s 角色中文名由后端下发" % label,
              dig(body, "data.roleLabel"), {"student": "学员", "teacher": "教师", "admin": "管理员"}[want_alias])
        check_true("%s 返回 accessToken" % label, len(token) > 40, "len=%d" % len(token))
        # 该字段是「引导期凭据文件是否仍在」的探针，必须存在且是布尔（前端据此提示）
        check_true("%s 响应含 mustChangePassword 布尔" % label,
                   isinstance(dig(body, "data.mustChangePassword"), bool),
                   repr(dig(body, "data.mustChangePassword")))
        tokens[want_alias] = token

    # 样本非空守卫（硬规则：假绿比 FAIL 危险）—— 三个 token 全齐才继续，
    # 否则后面的断言会因为"没有样本"而全体落空，却都算通过。
    if not all(tokens.values()):
        check_true("三个角色均拿到 accessToken", False, "tokens=%s" % {k: bool(v) for k, v in tokens.items()})
        return None
    return tokens


def section_2_capabilities(tokens):
    print("[2] 能力画像：GET /accounts/me/capabilities")
    profiles = {}
    for alias, expected_perms in (("student", EXPECTED_STUDENT),
                                  ("teacher", EXPECTED_TEACHER),
                                  ("admin", EXPECTED_STAFF)):
        st, body = caps_of(tokens[alias])
        check("%s 能力接口" % alias, dig(body, "code"), 200)
        got = perm_codes(body)
        check_true("%s 能力码集合与预期一致" % alias, got == expected_perms,
                   "got=%d want=%d diff=%s" % (len(got), len(expected_perms),
                                               sorted(got ^ expected_perms)))
        check("%s 能力码数量" % alias, len(got), len(expected_perms))
        check("%s 落地路由" % alias, dig(body, "data.home"), EXPECTED_HOME[alias])
        check("%s 角色别名" % alias, dig(body, "data.roleAlias"), alias)
        # 菜单的 perm 必须都在已授权集合里，否则前端会画出点进去 403 的入口
        perms_of_menus = {m.get("perm") for m in (dig(body, "data.menus", []) or [])}
        check_true("%s 菜单所需能力码均已授权" % alias, perms_of_menus <= got,
                   "越界=%s" % sorted(perms_of_menus - got))
        check_true("%s 菜单非空" % alias, len(menu_keys(body)) > 0, "menus=%d" % len(menu_keys(body)))
        profiles[alias] = body

    print("[3] 层次与 fail-closed：学员不得越级")
    student = perm_codes(profiles["student"])
    teacher = perm_codes(profiles["teacher"])
    admin = perm_codes(profiles["admin"])
    check_true("学员 ⊆ 教师", student <= teacher)
    check_true("教师 ⊆ 员工", teacher <= admin)
    check_true("学员 ⊊ 教师（严格子集）", student < teacher)

    leaked = [c for c in FORBIDDEN_FOR_STUDENT if c in student]
    check_true("学员未获得任何教师/管理面能力码", not leaked, "泄漏=%s" % leaked)

    for alias in ("student", "teacher"):
        menus = menu_keys(profiles[alias])
        bad = [k for k in menus if k in ("user-manage", "tag-manage", "worker-cluster", "monitor")]
        check_true("%s 不出现系统分组菜单" % alias, not bad, "越界=%s" % bad)

    check("员工看到全部菜单数", len(menu_keys(profiles["admin"])), 10)


def section_4_guards():
    print("[4] 未登录 / 坏 token：必须 401 且 fail-closed")
    st, body = caps_of(None)
    check_true("未带 token 请求能力接口被拒", st == 401 or dig(body, "code") == 401,
               "http=%d code=%s" % (st, dig(body, "code")))
    check_true("未登录时不返回任何能力码", not perm_codes(body))

    st, body = caps_of("not-a-jwt")
    check_true("伪造 token 被网关拒绝", st == 401 or dig(body, "code") == 401,
               "http=%d code=%s" % (st, dig(body, "code")))
    check_true("伪造 token 时不返回任何能力码", not perm_codes(body))


def section_5_legacy_entry():
    print("[5] 旧端点 POST /accounts/admin/login：兼容别名不得退化")
    st, body = request("POST", GW + "/accounts/admin/login",
                       {"cellPhone": PHONE_ADMIN, "password": PWD_ADMIN})
    check("员工走旧端点仍可登录", dig(body, "code"), 200)
    check("旧端点 role 仍为员工", dig(body, "data.role"), 1)

    st, body = request("POST", GW + "/accounts/admin/login",
                       {"cellPhone": PHONE_STUDENT, "password": PWD_SEED})
    check("学员走旧端点被拒（staffOnly 门槛仍在）", dig(body, "code"), 401)


SEED_PHONES = ("13900000001", "13900000002", "13900000003", "13900000004", "13900000005",
               "13900000011", "13900000012", "13800000000")


def strip_comments(text):
    """剥掉注释再扫描。

    必要性：本次改动恰恰在注释里写了「页脚刻意不再列出任何演示账号与口令」——
    不剥注释的话，解释"为什么删掉它"的那句话本身会被当成"还没删"。
    行内 `//` 只剥整行以 `//` 开头的，避免误伤 URL 里的 `//`。
    """
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"^\s*//.*$", "", text, flags=re.M)
    return text


def section_6_frontend_static():
    print("[6] 前端静态断言：不参与鉴权、不外显默认账密")
    if os.environ.get("SKIP_FRONTEND"):
        skip("前端静态断言", "SKIP_FRONTEND=1")
        return
    web = pathlib.Path(__file__).resolve().parent.parent / "judge-web"
    if not web.is_dir():
        skip("前端静态断言", "找不到 judge-web 目录")
        return

    src_files = [p for p in (web / "src").rglob("*")
                 if p.suffix in (".js", ".vue") and p.is_file()]
    check_true("前端源码文件可枚举", len(src_files) > 20, "files=%d" % len(src_files))

    # 剥注释后的源码：所有静态断言都在这个视图上做
    stripped = {p: strip_comments(p.read_text(encoding="utf-8", errors="replace"))
                for p in src_files}

    # ① 不得再出现角色判定 getter / 角色映射表残留
    role_smells = re.compile(r"\b(isStudent|isTeacher|isStaff|isAdmin|canManage|"
                             r"USER_TYPE|USER_TYPES|meta\.roles)\b")
    hits = []
    for p, text in stripped.items():
        for i, line in enumerate(text.splitlines(), 1):
            if role_smells.search(line):
                hits.append("%s:%d %s" % (p.relative_to(web), i, line.strip()[:70]))
    check_true("源码中无前端角色判定代码", not hits, "; ".join(hits[:3]))

    # ② 能力码驱动的渲染必须真的用上了
    directive = web / "src" / "directives" / "permission.js"
    check_true("v-perm 指令文件存在", directive.is_file())
    views_using_perm = sorted(p.name for p, text in stripped.items() if "v-perm" in text)
    check_true("至少 4 个视图用 v-perm 控制按钮渲染", len(views_using_perm) >= 4,
               "views=%s" % views_using_perm)
    can_calls = sum(text.count("user.can(") for text in stripped.values())
    check_true("存在 user.can(...) 的能力码判定", can_calls >= 5, "calls=%d" % can_calls)

    # ③ 登录页与产物里都不得出现默认账密
    assets = {p.relative_to(web).as_posix(): text for p, text in stripped.items()}
    dist = web / "dist"
    if dist.is_dir():
        for p in list(dist.rglob("*.js")) + list(dist.rglob("*.html")):
            assets[p.relative_to(web).as_posix()] = strip_comments(
                p.read_text(encoding="utf-8", errors="replace"))
    else:
        skip("产物默认账密扫描", "dist/ 不存在（先 npm run build）")

    cred_hits = []
    for name, text in assets.items():
        for phone in SEED_PHONES:
            if phone in text:
                cred_hits.append("%s 含手机号 %s" % (name, phone))
        if "演示账号" in text:
            cred_hits.append("%s 含「演示账号」文案" % name)
    check_true("源码/产物中无默认账密外显", not cred_hits, "; ".join(cred_hits[:3]))


def main():
    print("统一登录 + 能力码验收   网关：%s" % GW)
    print("=" * 66)
    st, body = request("GET", GW + "/actuator/health")
    if st == -1:
        print("x 网关 %s 不可达：%s" % (GW, dig(body, "__error__")))
        sys.exit(2)

    tokens = section_1_unified_login()
    if tokens:
        section_2_capabilities(tokens)
        section_4_guards()
        section_5_legacy_entry()
    else:
        print("  [SKIP] 能力码/守卫/兼容端点 —— 登录未通过")
    section_6_frontend_static()

    print("=" * 66)
    print("通过 %d 项，失败 %d 项，跳过 %d 项" % (PASS, FAIL, SKIP))
    # 硬规则：有跳过项时不能只看"失败 0"就放心 —— 把跳过显式提示出来
    if SKIP:
        print("⚠ 有 %d 项被跳过（不是通过），请确认跳过原因是否可接受" % SKIP)
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
