#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
CodeJudge —— 登出/禁用 token 吊销机制验收脚本

验收三个契约（对应 2026-09-28「登出吊销机制」改造）：

  A. 登出即吊销 access token：登出后旧 Bearer token 立即 401（不再有 30 分钟残留窗口）
  B. 登出即吊销 refresh cookie：被盗/复制的 refresh cookie 登出后无法续签（30 天寿命作废）
  C. 禁用即杀全部会话：管理员禁用账号后，该账号所有在途 token 立即 401（吊销纪元），
     重新启用后新登录正常 —— 新签发的 token iat 晚于纪元，不受历史纪元影响

用法：
    python scripts/verify-revocation.py
    GW=http://localhost:9080 python scripts/verify-revocation.py

前置：
    judge-gateway(9080) / judge-auth(9081) / judge-user(9082) 已启动，sql/seed.sql 已灌入。

凭据来源（与 verify-authz.py 同约定，不硬编码敏感值）：
    学员 13900000001 种子口令默认 123456；
    管理员口令读取顺序：PWD_ADMIN 环境变量 > .env 的 CJ_ADMIN_INIT_PASSWORD > 123456。

副作用：
    ① 把测试学员的 jti 拉黑（TTL=token 剩余寿命，自然过期）；
    ② 对测试学员写入一次用户级吊销纪元（Redis key TTL 30 天）。
    两者只影响"纪元之前签发"的 token，之后的新登录不受影响；不改动任何数据库数据。
    ⚠ 请勿在未灌 seed 的共享环境对真实管理员跑本脚本。

依赖：仅标准库。
────────────────────────────────────────────────────────────
"""

import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.request

GW = os.environ.get("GW", "http://localhost:9080").rstrip("/")

PHONE_STUDENT = os.environ.get("PHONE_STUDENT", "13900000001")
PHONE_ADMIN = os.environ.get("PHONE_ADMIN", "13800000000")
PWD_SEED = os.environ.get("PWD_SEED", "123456")

REFRESH_COOKIE = "judge-refresh-token"


def admin_password():
    """PWD_ADMIN 环境变量 > .env 的 CJ_ADMIN_INIT_PASSWORD > 历史默认 123456"""
    env_pwd = os.environ.get("PWD_ADMIN")
    if env_pwd:
        return env_pwd
    env_file = pathlib.Path(__file__).resolve().parent.parent / ".env"
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line.startswith("CJ_ADMIN_INIT_PASSWORD="):
                value = line.split("=", 1)[1].strip().strip('"').strip("'")
                if value:
                    return value
    return "123456"


PASS = 0
FAIL = 0


def ok(name, detail=""):
    global PASS
    PASS += 1
    print(f"  [PASS] {name}" + (f" —— {detail}" if detail else ""))


def bad(name, detail=""):
    global FAIL
    FAIL += 1
    print(f"  [FAIL] {name}" + (f" —— {detail}" if detail else ""))


def http(method, path, token=None, body=None, cookie=None):
    """返回 (status, dict_or_None, set_cookies:list)"""
    req = urllib.request.Request(GW + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if cookie:
        req.add_header("Cookie", cookie)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=10) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, _json(raw), resp.headers.get_all("Set-Cookie") or []
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        return e.code, _json(raw), []


def _json(raw):
    try:
        return json.loads(raw)
    except Exception:
        return None


def rejected(status, body):
    """业务异常经网关/底座可能表现为 HTTP 401/403 或 HTTP 200 + body.code 401/403/423"""
    if status in (401, 403):
        return True
    return status == 200 and body is not None and body.get("code") in (401, 403, 423)


def login_full(cell, pwd):
    """登录：返回 (access_token, user_id, refresh_cookie_value)"""
    status, body, set_cookies = http("POST", "/accounts/login",
                                     body={"cellPhone": cell, "password": pwd})
    if status != 200 or not body or body.get("code") != 200:
        raise RuntimeError(f"登录失败 {cell}: HTTP {status} {body}")
    data = body.get("data") or {}
    token = data.get("accessToken")
    if not token:
        raise RuntimeError(f"登录响应缺少 accessToken: {body}")
    refresh = None
    for sc in set_cookies:
        for part in sc.split(";"):
            part = part.strip()
            if part.startswith(REFRESH_COOKIE + "="):
                value = part.split("=", 1)[1]
                if value:
                    refresh = value
    return token, data.get("userId"), refresh


def main():
    global PASS, FAIL
    print(f"── CodeJudge token 吊销验收 @ {GW} ──────────────────────────")
    admin_pwd = admin_password()

    # ── A. 登出吊销 access token ─────────────────────────────────
    print("\nA. 登出即吊销 access token")
    try:
        token, uid, refresh = login_full(PHONE_STUDENT, PWD_SEED)
        status, body, _ = http("GET", "/accounts/me/capabilities", token=token)
        (ok if (status == 200 and body and body.get("code") == 200) else bad)(
            "登出前旧 token 可用", f"capabilities HTTP {status}")

        status, body, _ = http("POST", "/accounts/logout", token=token)
        (ok if status == 200 else bad)("登出成功", f"HTTP {status}")

        status, body, _ = http("GET", "/accounts/me/capabilities", token=token)
        (ok if rejected(status, body) else bad)(
            "登出后同一 token 立即被拒", f"HTTP {status} body={body}")
    except Exception as e:
        bad("A 场景异常", str(e))

    # ── B. 登出吊销 refresh cookie ───────────────────────────────
    print("\nB. 登出即吊销 refresh cookie（被盗 cookie 不得续签）")
    try:
        token, uid, refresh = login_full(PHONE_STUDENT, PWD_SEED)
        if not refresh:
            bad("登录响应携带 refresh cookie")
        else:
            # 与真实浏览器一致：登出请求同时携带 Bearer（吊销 access）与 HttpOnly cookie
            #（吊销 refresh）。服务端只吊销"出示"的 token，不无差别杀用户全部会话。
            status, body, _ = http("POST", "/accounts/logout", token=token,
                                   cookie=f"{REFRESH_COOKIE}={refresh}")
            (ok if status == 200 else bad)("登出成功（携带 cookie）", f"HTTP {status}")
            status, body, _ = http("GET", "/accounts/refresh",
                                   cookie=f"{REFRESH_COOKIE}={refresh}")
            (ok if rejected(status, body) else bad)(
                "登出后旧 refresh cookie 无法续签", f"HTTP {status} body={body}")
    except Exception as e:
        bad("B 场景异常", str(e))

    # ── C. 禁用即杀全部会话 ──────────────────────────────────────
    print("\nC. 管理员禁用账号 → 在途 token 立即失效；重新启用 → 新登录正常")
    try:
        admin_token, admin_uid, _ = login_full(PHONE_ADMIN, admin_pwd)
        token, uid, refresh = login_full(PHONE_STUDENT, PWD_SEED)
        if not uid or not admin_token:
            raise RuntimeError("登录响应缺少 userId/adminToken")

        status, body, _ = http("PUT", f"/users/{uid}/status/0", token=admin_token)
        (ok if (status == 200 and body and body.get("code") == 200) else bad)(
            "管理员禁用学员", f"HTTP {status} body={body}")

        status, body, _ = http("GET", "/accounts/me/capabilities", token=token)
        (ok if rejected(status, body) else bad)(
            "禁用后在途 access token 立即被拒（吊销纪元）", f"HTTP {status} body={body}")

        status, body, _ = http("GET", "/accounts/refresh",
                               cookie=f"{REFRESH_COOKIE}={refresh}" if refresh else None)
        (ok if rejected(status, body) else bad)(
            "禁用后 refresh 被拒（纪元 + 状态双兜底）", f"HTTP {status} body={body}")

        # 恢复：重新启用（脚本不残留禁用状态；吊销纪元只影响旧 token，新登录不受影响）
        status, body, _ = http("PUT", f"/users/{uid}/status/1", token=admin_token)
        (ok if (status == 200 and body and body.get("code") == 200) else bad)(
            "管理员重新启用学员", f"HTTP {status} body={body}")

        # 吊销纪元对齐下一秒边界（零宽恕窗口）：与禁用落在同一秒内签发的 token 会被杀，
        # 同秒重登录最多延迟 1 秒重试（见 AuthRedisKeys.nextRevocationEpoch 设计注释）。
        # 脚本全流程快于 1 秒，故先等纪元边界越过再重登录，避免新 token 被同秒误杀。
        time.sleep(1.2)
        token2, _, _ = login_full(PHONE_STUDENT, PWD_SEED)
        status, body, _ = http("GET", "/accounts/me/capabilities", token=token2)
        (ok if (status == 200 and body and body.get("code") == 200) else bad)(
            "启用后新登录 token 正常（新 iat 晚于纪元）", f"HTTP {status} body={body}")
    except Exception as e:
        bad("C 场景异常", str(e))

    print("\n──────────────────────────────────────────────")
    print(f"总计：{PASS} 通过 / {FAIL} 失败")
    if FAIL:
        sys.exit(1)
    print("✔ token 吊销机制全部契约通过")


if __name__ == "__main__":
    main()
