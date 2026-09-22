#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
CodeJudge —— P1 遗留项验收脚本（登录链路 + 教师注册加固）

覆盖 P1 三个待拍板项中的两个可执行项：
  ① 教师注册收紧为 STAFF-only  —— 网关白名单是否真的拦住了匿名自助注册
  ③ POST /accounts/login 双 Token 下发 —— 此前因"命令行带明文密码被安全策略拦截"从未实测

（② judge-common 移除 Redisson 属静态检查，由 `grep -rn Redisson` 覆盖，不在本脚本内。）

用法：
    python scripts/verify-p1-login.py
    GW=http://localhost:9080 AUTH=http://localhost:9081 python scripts/verify-p1-login.py

前置：
    judge-gateway(9080) / judge-auth(9081) / judge-user(9082) 已启动，sql/seed.sql 已灌入。

依赖：仅标准库（urllib / http.cookiejar）。不需要 requests，也不需要 curl。

────────────────────────────────────────────────────────────
凭据来源（不硬编码敏感值）：
    学员 13900000001 / teacher 13900000011 的种子密码默认 123456（见 sql/seed.sql）；
    管理员初始密码默认 123456（见 .env 的 CJ_ADMIN_INIT_PASSWORD）。
    均可用环境变量覆盖：PWD_SEED / PWD_ADMIN / PHONE_STUDENT / PHONE_ADMIN ...

为什么本脚本走网关（与 verify-p2.py 相反）？
    verify-p2.py 验的是服务层归属逻辑，故直连 9083 并手工构造身份头。
    本脚本验的恰恰是**网关本身**的行为（白名单、token 透传、身份头剥离），
    必须走 9080，否则测不到 AuthGlobalFilter。

副作用与清理：
    会创建 1 个教师账号 + 1 个学员账号，用管理员 token 删除（逻辑删除）。
    不会修改任何既有账号的密码。首次改密只测**失败路径**，不触发凭据文件删除。
────────────────────────────────────────────────────────────
"""

import base64
import http.cookiejar
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

GW = os.environ.get("GW", "http://localhost:9080")
AUTH = os.environ.get("AUTH", "http://localhost:9081")

# ---- 种子凭据（可用环境变量覆盖；默认值与 sql/seed.sql / .env 一致）----
PWD_SEED = os.environ.get("PWD_SEED", "123456")
PWD_ADMIN = os.environ.get("PWD_ADMIN", "123456")
PHONE_STUDENT = os.environ.get("PHONE_STUDENT", "13900000001")
UID_STUDENT = int(os.environ.get("UID_STUDENT", "2001"))
PHONE_ADMIN = os.environ.get("PHONE_ADMIN", "13800000000")

REFRESH_COOKIE = "judge-refresh-token"
ADMIN_REFRESH_COOKIE = "judge-admin-refresh-token"

PASS = 0
FAIL = 0


# ==================== HTTP 基础设施 ====================

class Client:
    """带 cookie jar 的极简 HTTP 客户端。

    注意：服务端业务错误返回 HTTP 200 + body.code（R<T> 约定），
    断言因此统一看 body.code，只有网关自身的拒绝才走 HTTP 状态码。
    """

    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar))

    def request(self, method, url, body=None, headers=None, follow=True):
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            resp = self.opener.open(req, timeout=15)
            status, raw, hdrs = resp.status, resp.read().decode("utf-8"), resp.headers
        except urllib.error.HTTPError as e:
            status, raw, hdrs = e.code, e.read().decode("utf-8", errors="replace"), e.headers
        except Exception as e:
            return -1, {"__error__": str(e)}, {}
        try:
            return status, json.loads(raw), hdrs
        except Exception:
            return status, {"__raw__": raw[:300]}, hdrs

    def cookie(self, name):
        for c in self.jar:
            if c.name == name:
                return c.value
        return None

    def clear_cookies(self):
        self.jar.clear()


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
        print("  [PASS] %-54s %s" % (label, actual))
        PASS += 1
    else:
        print("  [FAIL] %-54s got=%r want=%r" % (label, actual, expected))
        FAIL += 1


def check_true(label, cond, detail=""):
    check(label + ("" if not detail else " (%s)" % detail), bool(cond), True)


def jwt_claim(token, key):
    """无验签解析 JWT payload（仅测试断言用），失败返回 None。"""
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        return json.loads(base64.urlsafe_b64decode(payload)).get(key)
    except Exception:
        return None


def rand_phone(prefix="139"):
    """生成一个不撞种子的手机号（种子集中在 139000000 段）。"""
    return prefix + str(int(time.time() * 1000) % 100000000).zfill(8)


def qs(params):
    """查询串编码。务必用它拼 URL —— 直接把中文写进 URL 会让 urllib 发出非法请求。

    另外注意端点选择：/users/page 只支持 type 过滤（无 keyword），
    带关键字的按角色检索要用 /teachers/page 与 /students/page。
    """
    return urllib.parse.urlencode(params)


def find_user(gw, role_path, keyword, admin_access):
    """按关键字（用手机号，ASCII 最稳）在 /teachers/page 或 /students/page 中定位账号。"""
    st, r, _ = gw.request("GET", GW + role_path + "?" + qs(
        {"pageSize": 100, "keyword": keyword}),
        headers={"Authorization": "Bearer " + admin_access})
    lst = dig(r, "data.list", []) or []
    return lst[0] if lst else None


def preclean(gw, admin_access):
    """幂等预清理：删掉上一次中断运行残留的『验收临时』账号。"""
    removed = 0
    for role_path in ("/teachers/page", "/students/page"):
        st, r, _ = gw.request("GET", GW + role_path + "?" + qs(
            {"pageSize": 100, "keyword": "验收临时"}),
            headers={"Authorization": "Bearer " + admin_access})
        for u in (dig(r, "data.list", []) or []):
            st, rr, _ = gw.request("DELETE", GW + "/users/%s" % u.get("id"),
                                   headers={"Authorization": "Bearer " + admin_access})
            if dig(rr, "code") == 200:
                removed += 1
    return removed


# ==================== 断言主体 ====================

def main():
    print("网关：%s    认证：%s" % (GW, AUTH))
    print("=" * 66)
    c = Client()

    # ---------------------------------------------------------------
    print("[1] 学员登录（直连 judge-auth，P1 项③：双 Token 下发）")
    st, r, hdrs = c.request("POST", AUTH + "/accounts/login",
                            {"cellPhone": PHONE_STUDENT, "password": PWD_SEED})
    check("HTTP 状态码", st, 200)
    check("body.code", dig(r, "code"), 200)
    access = dig(r, "data.accessToken") or ""
    refresh = dig(r, "data.refreshToken") or ""
    check("data.userId", dig(r, "data.userId"), UID_STUDENT)
    check_true("accessToken 为三段式 JWT", access.count(".") == 2,
               "segments=%d" % (access.count(".") + 1))
    check_true("accessToken 非空", len(access) > 40, "len=%d" % len(access))
    check_true("refreshToken 非空", len(refresh) > 40, "len=%d" % len(refresh))
    check("data.expireTime（秒）", dig(r, "data.expireTime"), 1800)
    check("accessToken 的 userId claim", jwt_claim(access, "userId"), UID_STUDENT)
    check("accessToken 的 roleId claim（学员=2）", jwt_claim(access, "roleId"), 2)
    # Cookie 下发
    set_cookie = ",".join(hdrs.get_all("Set-Cookie") or []) if hasattr(hdrs, "get_all") else ""
    check_true("Set-Cookie 下发 refresh cookie",
               REFRESH_COOKIE in set_cookie, REFRESH_COOKIE)
    check_true("refresh cookie 为 HttpOnly",
               "httponly" in set_cookie.lower(), "HttpOnly")
    check_true("refresh cookie 带 SameSite",
               "samesite" in set_cookie.lower(), "SameSite")
    check_true("refresh cookie 不进入响应体（仅 Cookie）",
               REFRESH_COOKIE not in json.dumps(r))

    # ---------------------------------------------------------------
    print("[2] 登录负例（凭据校验与账号类型隔离）")
    st, r, _ = c.request("POST", AUTH + "/accounts/login",
                         {"cellPhone": PHONE_STUDENT, "password": "wrong-password"})
    check("错误密码被拒", dig(r, "code"), 401)
    st, r, _ = c.request("POST", AUTH + "/accounts/login",
                         {"cellPhone": "19999999999", "password": PWD_SEED})
    check("不存在的手机号被拒", dig(r, "code"), 401)
    st, r, _ = c.request("POST", AUTH + "/accounts/login",
                         {"cellPhone": PHONE_STUDENT, "password": ""})
    check("空密码被拒（400 参数校验）", dig(r, "code"), 400)
    st, r, _ = c.request("POST", AUTH + "/accounts/admin/login",
                         {"cellPhone": PHONE_STUDENT, "password": PWD_SEED})
    check("学员走管理端登录被拒（非管理员账号）", dig(r, "code"), 401)

    # ---------------------------------------------------------------
    print("[3] 管理员登录（管理端）")
    ac = Client()
    st, r, _ = ac.request("POST", AUTH + "/accounts/admin/login",
                          {"cellPhone": PHONE_ADMIN, "password": PWD_ADMIN})
    check("管理员登录 body.code", dig(r, "code"), 200)
    admin_access = dig(r, "data.accessToken") or ""
    check("管理员 roleId claim（员工=1）", jwt_claim(admin_access, "roleId"), 1)
    check_true("管理端 refresh cookie 独立命名",
               ac.cookie(ADMIN_REFRESH_COOKIE) is not None, ADMIN_REFRESH_COOKIE)

    # ---------------------------------------------------------------
    print("[4] Token 刷新")
    st, r, _ = c.request("POST", AUTH + "/accounts/refresh")
    check("携带 refresh cookie 可刷新", dig(r, "code"), 200)
    check_true("刷新后 accessToken 非空", len(dig(r, "data.accessToken") or "") > 40)
    check("刷新不返回 refreshToken（不轮换）", dig(r, "data.refreshToken"), None)
    bare = Client()
    st, r, _ = bare.request("POST", AUTH + "/accounts/refresh")
    check("无 refresh cookie 刷新被拒", dig(r, "code"), 401)

    # ---------------------------------------------------------------
    print("[5] 经网关端到端（AuthGlobalFilter 白名单 + 身份透传）")
    gw = Client()
    st, r, _ = gw.request("POST", GW + "/accounts/login",
                          {"cellPhone": PHONE_STUDENT, "password": PWD_SEED})
    check("经网关登录（白名单命中）", dig(r, "code"), 200)
    gw_access = dig(r, "data.accessToken") or access

    st, r, _ = gw.request("GET", GW + "/users/me",
                          headers={"Authorization": "Bearer " + gw_access})
    check("携带 token 访问 /users/me", dig(r, "code"), 200)
    check("身份由网关注入（userId 一致）", dig(r, "data.id"), UID_STUDENT)

    st, r, _ = gw.request("GET", GW + "/users/me")
    check("无 token 访问 /users/me", dig(r, "code"), 401)

    st, r, _ = gw.request("GET", GW + "/users/me",
                          headers={"user-info": "2001", "role-info": "1"})
    check("伪造 user-info 头被网关拒绝", dig(r, "code"), 401)

    # ---------------------------------------------------------------
    print("[6] 教师注册加固（P1 项①：/teachers/register 已移出白名单）")
    created = []          # (label, userId) 供收尾删除
    left = preclean(gw, admin_access)
    if left:
        print("  [INFO] 预清理上次残留的临时账号 %d 个" % left)
    p_anon = rand_phone()

    st, r, _ = gw.request("POST", GW + "/teachers/register",
                          {"cellPhone": p_anon, "password": PWD_SEED, "name": "验收临时教师"})
    check("匿名注册教师被拒（网关白名单已移除）", dig(r, "code"), 401)

    st, r, _ = gw.request("POST", GW + "/teachers/register",
                          {"cellPhone": p_anon, "password": PWD_SEED, "name": "验收临时教师"},
                          headers={"Authorization": "Bearer " + gw_access})
    check("学员 token 注册教师被拒（@RequireRole STAFF）", dig(r, "code"), 403)

    # 学员自助注册仍应放行（白名单保留，回归保护）
    p_stu = rand_phone()
    st, r, _ = gw.request("POST", GW + "/students/register",
                          {"cellPhone": p_stu, "password": PWD_SEED, "name": "验收临时学员"})
    check("学员匿名自助注册仍放行（回归保护）", dig(r, "code"), 200)

    # 管理员开通教师 → 新教师能登录 → 证明"教师如何获得账号"链路成立
    p_teacher = rand_phone()
    st, r, _ = gw.request("POST", GW + "/teachers/register",
                          {"cellPhone": p_teacher, "password": PWD_SEED, "name": "验收临时教师",
                           "type": 1},  # 故意传 type=1，验证后端强制改写为 3，防越权注册管理员
                          headers={"Authorization": "Bearer " + admin_access})
    check("管理员开通教师成功", dig(r, "code"), 200)

    tch = find_user(gw, "/teachers/page", p_teacher, admin_access)
    check("新教师出现在教师列表", bool(tch), True)
    if tch:
        check("后端强制覆盖越权 type（仍为教师=3）", tch.get("type"), 3)
        created.append(("教师", tch.get("id")))

    login_new = Client()
    st, r, _ = login_new.request("POST", AUTH + "/accounts/login",
                                 {"cellPhone": p_teacher, "password": PWD_SEED})
    check("新开通教师可登录", dig(r, "code"), 200)
    check("新教师 roleId claim（教师=3）",
          jwt_claim(dig(r, "data.accessToken") or "", "roleId"), 3)

    stu = find_user(gw, "/students/page", p_stu, admin_access)
    if stu:
        created.append(("学员", stu.get("id")))

    # ---------------------------------------------------------------
    print("[7] 首次改密 fail-closed（只测失败路径，不触发凭据文件删除）")
    st, r, _ = gw.request("POST", GW + "/accounts/password/first-change",
                          {"cellPhone": PHONE_ADMIN, "oldPassword": "definitely-wrong",
                           "newPassword": "NewPwd_2026!"})
    check("原密码错误被拒", dig(r, "code"), 400)
    cred = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "..", "judge-auth", ".bootstrap-credentials")
    cred = os.path.normpath(cred)
    check("校验失败时不删除初始凭据文件（fail-closed）", os.path.exists(cred), True)

    # ---------------------------------------------------------------
    print("[8] 收尾清理（逻辑删除本脚本创建的临时账号）")
    for label, uid in created:
        st, r, _ = gw.request("DELETE", GW + "/users/%s" % uid,
                              headers={"Authorization": "Bearer " + admin_access})
        check("已删除临时%s id=%s" % (label, uid), dig(r, "code"), 200)
    if not created:
        print("  [SKIP] 无临时账号需清理")

    print("=" * 66)
    print("通过 %d 项，失败 %d 项" % (PASS, FAIL))
    return 1 if FAIL else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
