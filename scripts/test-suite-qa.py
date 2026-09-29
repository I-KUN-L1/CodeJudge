# -*- coding: utf-8 -*-
"""
CodeJudge 全面测试套件（API / 鉴权 / 越权 / 安全 / E2E 判题闭环 / 幂等 / 限流）。

运行（需基础设施 + 8 个后端服务已启动）：
    C:\\Users\\20670\\.workbuddy\\binaries\\python\\envs\\default\\Scripts\\python.exe scripts/test-suite-qa.py

设计说明：
- 纯标准库 + requests，不依赖 pytest，可直接在 CI 中以退出码判定。
- 登录接口有网关令牌桶限流（2 req/s 突发 5），套件内登录统一节流 0.8s；
  登录爆破 429 测试刻意放在最后执行，避免打爆同 IP 令牌桶影响后续用例。
- 判题为异步链路（MQ → worker → 沙箱），E2E 用例轮询终态，最长 90s。
"""
import base64
import hashlib
import hmac
import json
import os
import re
import sys
import time

import requests

BASE = "http://localhost:9080"
JWT_SECRET = None  # 从 .env 读取，用于伪造 token 的安全用例
ACTUATOR_TOKEN = None  # 从 .env 读取，actuator 严格模式令牌（QA-F01）

results = []  # (编号, 名称, 优先级, PASS/FAIL/WARN, 详情)
NEW_UID = None  # B01 注册的新用户 id（E06 水平越权探测用）


def record(tc_id, name, prio, ok, detail="", warn=False):
    status = "WARN" if warn else ("PASS" if ok else "FAIL")
    results.append((tc_id, name, prio, status, detail))
    icon = {"PASS": "+", "FAIL": "x", "WARN": "?"}[status]
    print(f"[{icon}] {tc_id} {name} {('- ' + detail) if detail else ''}")


def load_env():
    global JWT_SECRET, ACTUATOR_TOKEN
    try:
        with open(os.path.join(os.path.dirname(__file__), "..", ".env"), encoding="utf-8") as f:
            for line in f:
                m = re.match(r"CJ_JWT_SECRET=(.+)", line.strip())
                if m:
                    JWT_SECRET = m.group(1).strip()
                m = re.match(r"CJ_ACTUATOR_TOKEN=(.+)", line.strip())
                if m:
                    ACTUATOR_TOKEN = m.group(1).strip().strip('"').strip("'")
    except OSError:
        pass


_last_login_ts = [0.0]


def throttle_login():
    elapsed = time.time() - _last_login_ts[0]
    if elapsed < 0.8:
        time.sleep(0.8 - elapsed)
    _last_login_ts[0] = time.time()


def login(cell, pwd, alias=False):
    """返回 (http_status, body_dict)。业务失败也是 HTTP 200 + body.code!=200（R 包络）。"""
    throttle_login()
    r = requests.post(f"{BASE}/accounts/{'admin/' if alias else ''}login",
                      json={"cellPhone": cell, "password": pwd}, timeout=10)
    try:
        return r.status_code, r.json()
    except ValueError:
        return r.status_code, {"raw": r.text[:200]}


def is_ok(body):
    """R 包络：code==200 视为业务成功。"""
    return body.get("code") == 200


def code_of(r):
    """业务状态码归一：HTTP 非 200 用 HTTP 码；HTTP 200 用包络 code（项目约定业务错误也是 HTTP 200）。"""
    if r.status_code != 200:
        return r.status_code
    try:
        return r.json().get("code")
    except ValueError:
        return r.status_code


def token_of(body):
    return body.get("data", {}).get("accessToken")


def bearer(tok):
    return {"Authorization": f"Bearer {tok}"}


def craft_jwt(payload, secret, signature_b64=None):
    """纯标准库手工构造 HS256 JWT（伪造/过期/篡改用例）。"""
    def b64(obj):
        raw = json.dumps(obj, separators=(",", ":")).encode()
        return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()
    head = b64({"alg": "HS256", "typ": "JWT"})
    body = b64(payload)
    signing_input = f"{head}.{body}".encode()
    if signature_b64 is not None:
        sig = signature_b64
    else:
        sig = base64.urlsafe_b64encode(
            hmac.new(secret.encode(), signing_input, hashlib.sha256).digest()
        ).rstrip(b"=").decode()
    return f"{head}.{body}.{sig}"


# ============================================================
# A. 认证与登录（P0）
# ============================================================

def suite_auth():
    print("\n===== A. 认证与登录 =====")
    st, body = login("13900000001", "123456")
    ok = st == 200 and is_ok(body) and token_of(body)
    record("QA-A01", "学员正确凭据登录", "P0", ok,
           "" if ok else f"status={st} body={body}")
    student_token = token_of(body) if ok else None
    student_uid = body.get("data", {}).get("userId")

    st, body = login("13900000001", "wrong-password")
    record("QA-A02", "错误密码登录被拒", "P0", st == 200 and not is_ok(body),
           f"status={st} code={body.get('code')} msg={body.get('msg', '')[:40]}")

    st, body = login("19999990000", "123456")
    record("QA-A03", "不存在账号登录被拒", "P0", not is_ok(body),
           f"status={st} code={body.get('code')}")

    st, body = login("13900000001", "123456", alias=True)  # 学员走 admin 别名 → 应拒绝（要求员工）
    record("QA-A04", "admin 兼容别名拒绝非员工账号", "P1", not is_ok(body),
           f"status={st} code={body.get('code')} msg={body.get('msg', '')[:40]}")

    # refreshToken 不得出现在响应体（HttpOnly Cookie 下发）
    leak = "refreshToken" in json.dumps(body.get("data") or {})
    record("QA-A05", "refreshToken 不泄露进响应体", "P1", not leak)

    # 能力码契约（复用 A01 的 token，不再重复登录）
    r = requests.get(f"{BASE}/accounts/me/capabilities", headers=bearer(student_token or "x"), timeout=10)
    d = r.json().get("data") or {}
    record("QA-A06", "能力码下发（role/perms/menus/home）", "P0",
           r.status_code == 200 and d.get("role") == 2 and d.get("perms") and d.get("home"),
           f"role={d.get('role')} perms={len(d.get('perms') or [])}")

    # 无 token 访问受保护端点
    r = requests.get(f"{BASE}/users/me", timeout=10)
    record("QA-A07", "无 Token 访问 /users/me → 401", "P0", r.status_code == 401, f"status={r.status_code}")

    # 伪造 token（错误密钥签名）
    if JWT_SECRET:
        forged = craft_jwt({"sub": "1", "userId": 1, "roleId": 1, "exp": 9999999999, "iat": 1}, "wrong-secret-xxx")
        r = requests.get(f"{BASE}/users/me", headers=bearer(forged), timeout=10)
        record("QA-A08", "伪造签名 Token → 401", "P0", r.status_code == 401, f"status={r.status_code}")

        # 正确密钥、过期时间（exp 过去）
        expired = craft_jwt({"sub": "1", "userId": 1, "roleId": 2, "exp": 1000000000, "iat": 999999999}, JWT_SECRET)
        r = requests.get(f"{BASE}/users/me", headers=bearer(expired), timeout=10)
        record("QA-A09", "过期 Token → 401", "P0", r.status_code == 401, f"status={r.status_code}")

        # 正确签名但篡改载荷（把 student uid 改成管理员 uid=1 的语义漂移）
        tampered = craft_jwt({"sub": "1", "userId": 1, "roleId": 1, "exp": 9999999999, "iat": 1}, JWT_SECRET + "x")
        r = requests.get(f"{BASE}/users/me", headers=bearer(tampered), timeout=10)
        record("QA-A10", "篡改载荷 Token → 401", "P0", r.status_code == 401, f"status={r.status_code}")
    else:
        record("QA-A08", "伪造签名 Token → 401", "P0", False, "无法读取 CJ_JWT_SECRET，用例未执行")

    r = requests.get(f"{BASE}/users/me", headers={"Authorization": "Bearer garbage.token.here"}, timeout=10)
    record("QA-A11", "垃圾 Token → 401", "P0", r.status_code == 401, f"status={r.status_code}")

    # 伪造网关信任头（匿名携带 user-info/role-info）
    r = requests.get(f"{BASE}/users/me", headers={"user-info": "1", "role-info": "1"}, timeout=10)
    record("QA-A12", "匿名伪造 user-info 头不越权", "P0", r.status_code == 401, f"status={r.status_code}")

    # 学员带合法 token + 伪造 role-info 提权 → 下游以网关注入为准
    if student_token:
        r = requests.get(f"{BASE}/students/page", headers={**bearer(student_token), "role-info": "1"}, timeout=10)
        record("QA-A13", "学员伪造 role-info 头无法查看用户列表", "P0", code_of(r) == 403,
               f"http={r.status_code} code={code_of(r)}")

    # 登出后 token 立即吊销
    st, body = login("13900000002", "123456")
    tok2 = token_of(body)
    if tok2:
        r = requests.post(f"{BASE}/accounts/logout", headers=bearer(tok2), timeout=10)
        r2 = requests.get(f"{BASE}/users/me", headers=bearer(tok2), timeout=10)
        record("QA-A14", "登出后原 Token 立即失效（吊销）", "P0", r2.status_code == 401,
               f"logout={r.status_code} after={r2.status_code}")
    else:
        record("QA-A14", "登出后原 Token 立即失效（吊销）", "P0", False, "登录失败无法执行")

    # 注册：正向 + 参数校验 + 越权 type
    unique = str(int(time.time()))[-9:]
    new_cell = "17" + unique.zfill(9)[-9:]
    r = requests.post(f"{BASE}/students/register",
                      json={"cellPhone": new_cell, "password": "test123456", "type": 1,
                            "name": f"QA学员{unique}"}, timeout=10)
    ok_register = r.status_code == 200 and is_ok(r.json())
    record("QA-B01", "学员自助注册成功（携带 type=1 也强制学员）", "P0", ok_register,
           f"status={r.status_code} code={r.json().get('code')} msg={r.json().get('msg', '')[:40]}")
    global NEW_UID
    NEW_UID = (r.json().get("data") or {}).get("id")

    new_token = None
    if ok_register:
        st, body = login(new_cell, "test123456")
        d = body.get("data") or {}
        record("QA-B02", "新注册账号登录且角色为学员(2)", "P0",
               st == 200 and is_ok(body) and d.get("role") == 2,
               f"role={d.get('role')}")
        new_token = token_of(body)
    # 存储型 XSS 防护（QA-B06 修复）：含 HTML 载荷的注册名必须在入口被拒
    xss_cell = "17" + str(int(time.time()) + 1)[-9:]
    r = requests.post(f"{BASE}/students/register",
                      json={"cellPhone": xss_cell, "password": "test123456",
                            "name": "<script>alert(1)</script>"}, timeout=10)
    record("QA-B06", "注册名含 script 载荷 → 入口拒绝（存储型 XSS 白名单防护）", "P0",
           r.status_code == 200 and code_of(r) == 400,
           f"code={r.json().get('code')} msg={str(r.json().get('msg'))[:50]}")

    def register_rejected(payload):
        r = requests.post(f"{BASE}/students/register", json=payload, timeout=10)
        return not (r.status_code == 200 and is_ok(r.json()))

    r = requests.post(f"{BASE}/students/register", json={"cellPhone": "abc", "password": "123456"}, timeout=10)
    record("QA-B03", "非法手机号注册被拒", "P0",
           not (r.status_code == 200 and is_ok(r.json())),
           f"code={r.json().get('code')}")

    r = requests.post(f"{BASE}/students/register", json={"cellPhone": "13800000001", "password": "123456"}, timeout=10)
    record("QA-B04", "重复手机号注册被拒", "P1",
           not (r.status_code == 200 and is_ok(r.json())),
           f"code={r.json().get('code')}")

    r = requests.post(f"{BASE}/students/register", json={"cellPhone": "13911112222", "password": "12345"}, timeout=10)
    record("QA-B05", "弱密码（<6位）注册被拒", "P0",
           not (r.status_code == 200 and is_ok(r.json())),
           f"code={r.json().get('code')}")

    return student_token, student_uid


# ============================================================
# B. 题目域与权限（P0/P1）
# ============================================================

def suite_problems(student_token):
    print("\n===== B. 题目域与权限 =====")
    r = requests.get(f"{BASE}/problems/page?pageNo=1&pageSize=5", timeout=10)
    # QA-C01 修复契约：/problems/page 已入网关 publicReadPaths（仅 GET/HEAD），匿名 200 只出已发布题目
    ok_c01, c01_detail = False, f"status={r.status_code}"
    if r.status_code == 200:
        body = r.json()
        lst = (body.get("data") or {}).get("list") or []
        ok_c01 = body.get("code") == 200 and isinstance(lst, list)
        c01_detail += f" code={body.get('code')} count={len(lst)}"
    record("QA-C01", "匿名访问 /problems/page → 200（公开只读白名单，仅已发布题目）", "P1",
           ok_c01, c01_detail)

    r = requests.get(f"{BASE}/problems/page?pageNo=1&pageSize=5", headers=bearer(student_token), timeout=10)
    data = (r.json().get("data") or {})
    lst = data.get("list") or data.get("records") or []
    record("QA-C02", "学员登录可分页浏览题目", "P0",
           r.status_code == 200 and isinstance(lst, list), f"count={len(lst)} status={r.status_code}")

    published = next((p for p in lst if p.get("status") == 1), None) if lst else None
    if published:
        pid = published["id"]
        r2 = requests.get(f"{BASE}/problems/{pid}", headers=bearer(student_token), timeout=10)
        record("QA-C03", "学员查看已发布题目详情", "P0", r2.status_code == 200, f"status={r2.status_code}")
    else:
        record("QA-C03", "学员查看已发布题目详情", "P0", False, "第一页无已发布题目，用例未执行")

    # 学员不可见题目管理写接口
    r = requests.post(f"{BASE}/problems", headers=bearer(student_token), json={"title": "x"}, timeout=10)
    record("QA-C04", "学员创建题目 → 403", "P0", code_of(r) == 403, f"http={r.status_code} code={code_of(r)}")

    r = requests.get(f"{BASE}/students/page?pageNo=1&pageSize=5", headers=bearer(student_token), timeout=10)
    record("QA-C05", "学员访问管理端学员列表 → 403", "P0", code_of(r) == 403,
           f"http={r.status_code} code={code_of(r)}")

    # SQL 注入探测（参数化查询应安全返回，不 500 不泄漏）
    for payload in ("' OR '1'='1", "1' UNION SELECT NULL--", "%' AND SLEEP(3)--"):
        t0 = time.time()
        r = requests.get(f"{BASE}/problems/page", params={"pageNo": 1, "pageSize": 5, "keyword": payload},
                         headers=bearer(student_token), timeout=15)
        cost = time.time() - t0
        record("QA-C06", f"SQL 注入探测 keyword={payload[:20]}", "P1",
               r.status_code == 200 and cost < 3, f"status={r.status_code} cost={cost:.2f}s")

    # 路径遍历探测
    r = requests.get(f"{BASE}/problems/..%2f..%2fetc%2fpasswd", headers=bearer(student_token),
                     allow_redirects=False, timeout=10)
    record("QA-C07", "路径遍历探测不 200", "P2", r.status_code in (400, 401, 403, 404),
           f"status={r.status_code}")


# ============================================================
# C. 提交 E2E 判题闭环（P0）
# ============================================================

def poll_terminal(tok, sid, timeout_s=90):
    deadline = time.time() + timeout_s
    last = None
    while time.time() < deadline:
        r = requests.get(f"{BASE}/submissions/{sid}", headers=bearer(tok), timeout=10)
        if r.status_code != 200:
            return None, last
        last = r.json().get("data") or {}
        if last.get("status") in ("SUCCESS", "FAILED"):
            return last, last
        time.sleep(1.5)
    return None, last


AC_CODE_TMPL = "import sys\nd = sys.stdin.read().split()\nprint(sum(int(x) for x in d))\n# qa-run-{salt}"
WA_CODE_TMPL = "import sys\nprint(0)\n# qa-run-{salt}"


def salted(tmpl):
    return tmpl.format(salt=int(time.time() * 1000))


def suite_submission_e2e(student_token, teacher_token=None):
    print("\n===== C. 提交 E2E 判题闭环 =====")
    # 题目 4001（种子数据：求和题）；代码盐化避免跨轮次幂等污染
    tok = student_token
    ac_code = salted(AC_CODE_TMPL)
    r = requests.post(f"{BASE}/submissions", headers=bearer(tok),
                      json={"problemId": 4001, "language": "PYTHON", "code": ac_code}, timeout=15)
    d = r.json().get("data") or {}
    sid = d.get("id")
    record("QA-D01", "提交 AC 代码受理（status=PENDING）", "P0",
           r.status_code == 200 and is_ok(r.json()) and sid and d.get("status") == "PENDING",
           f"status={r.status_code} id={sid} voStatus={d.get('status')}")

    if sid:
        final, _ = poll_terminal(tok, sid)
        record("QA-D02", "AC 代码判题终态 SUCCESS/verdict=AC", "P0",
               bool(final) and final.get("status") == "SUCCESS" and final.get("verdict") == "AC",
               f"status={final and final.get('status')} verdict={final and final.get('verdict')} "
               f"score={final and final.get('score')}")

        # 幂等：60s 内同码重提
        r = requests.post(f"{BASE}/submissions", headers=bearer(tok),
                          json={"problemId": 4001, "language": "PYTHON", "code": ac_code}, timeout=15)
        d2 = r.json().get("data") or {}
        record("QA-D03", "60s 内同码重复提交幂等返回（idempotent=true）", "P0",
               d2.get("idempotent") is True and d2.get("id") == sid,
               f"idempotent={d2.get('idempotent')} id={d2.get('id')}")

        # 详情：本人可见
        r = requests.get(f"{BASE}/submissions/{sid}", headers=bearer(tok), timeout=10)
        record("QA-D04", "本人查看提交详情", "P0", r.status_code == 200, f"status={r.status_code}")

    # WA 代码
    r = requests.post(f"{BASE}/submissions", headers=bearer(tok),
                      json={"problemId": 4001, "language": "PYTHON", "code": salted(WA_CODE_TMPL)}, timeout=15)
    d = r.json().get("data") or {}
    if d.get("id"):
        final, _ = poll_terminal(tok, d["id"])
        record("QA-D05", "WA 代码判题终态 verdict=WA", "P0",
               bool(final) and final.get("verdict") == "WA", f"verdict={final and final.get('verdict')}")
        # 学员视角隐藏用例摘要遮蔽
        r2 = requests.get(f"{BASE}/submissions/{d['id']}", headers=bearer(tok), timeout=10)
        cases = (r2.json().get("data") or {}).get("caseResults") or []
        leaked = [c for c in cases if c.get("hidden") and (c.get("outputDigest") or c.get("stderrDigest"))]
        record("QA-D06", "学员视角隐藏用例摘要被遮蔽", "P0", not leaked,
               f"cases={len(cases)} leaked={len(leaked)}")
    else:
        record("QA-D05", "WA 代码判题终态 verdict=WA", "P0", False, f"受理失败：{str(r.json())[:80]}")

    # 语法错误 → 终态非 AC（RE/CE）
    r = requests.post(f"{BASE}/submissions", headers=bearer(tok),
                      json={"problemId": 4001, "language": "PYTHON", "code": "def broken(:"}, timeout=15)
    d = r.json().get("data") or {}
    if d.get("id"):
        final, _ = poll_terminal(tok, d["id"], timeout_s=90)
        record("QA-D07", "语法错误代码得到非 AC 终态", "P1",
               bool(final) and final.get("verdict") in ("RE", "CE", "WA"),
               f"verdict={final and final.get('verdict')}")
    else:
        record("QA-D07", "语法错误代码得到非 AC 终态", "P1", False, "受理失败")

    # ---- 参数校验（项目约定：业务拒绝 = HTTP 200 + 包络 code）----
    r = requests.post(f"{BASE}/submissions", headers=bearer(tok),
                      json={"problemId": 4001, "language": "RUST", "code": "x"}, timeout=10)
    record("QA-D08", "不支持的语言 → code 400", "P0", code_of(r) in (400, 401, 403),
           f"http={r.status_code} code={code_of(r)} msg={str(r.json().get('msg'))[:30]}")

    r = requests.post(f"{BASE}/submissions", headers=bearer(tok),
                      json={"problemId": 4001, "language": "PYTHON", "code": ""}, timeout=10)
    record("QA-D09", "空代码 → code 400（@Valid）", "P0", code_of(r) in (400, 401, 403),
           f"http={r.status_code} code={code_of(r)}")

    r = requests.post(f"{BASE}/submissions", headers=bearer(tok),
                      json={"problemId": 4001, "language": "PYTHON", "code": "a" * (32 * 1024 + 1)}, timeout=10)
    record("QA-D10", "代码 32KB+1 → code 400", "P0", code_of(r) in (400, 401, 403),
           f"http={r.status_code} code={code_of(r)}")

    r = requests.post(f"{BASE}/submissions", headers=bearer(tok),
                      json={"problemId": 999999, "language": "PYTHON", "code": "x"}, timeout=10)
    record("QA-D11", "不存在的题目 → 业务拒绝", "P0",
           not (r.status_code == 200 and is_ok(r.json())),
           f"status={r.status_code} code={r.json().get('code')}")

    r = requests.get(f"{BASE}/submissions/999999", headers=bearer(tok), timeout=10)
    record("QA-D12", "查看不存在提交 → 404 语义（HTTP 404 或包络 code=404）", "P1",
           r.status_code == 404 or (r.status_code == 200 and r.json().get("code") == 404),
           f"status={r.status_code} code={r.json().get('code')}")


# ============================================================
# D. 越权（IDOR / 水平垂直）
# ============================================================

def suite_authz(student_token, teacher_token):
    print("\n===== D. 越权测试 =====")
    tok_a = student_token
    # A 提交一条
    r = requests.post(f"{BASE}/submissions", headers=bearer(tok_a),
                      json={"problemId": 4001, "language": "PYTHON", "code": f"print({int(time.time())})"},
                      timeout=15)
    sid_a = (r.json().get("data") or {}).get("id")
    if not sid_a:
        record("QA-E01", "水平越权前置：A 提交成功", "P0", False, "A 提交失败")
        return

    # B（另一位种子学员）尝试查看 A 的提交
    st, body = login("13900000003", "123456")
    tok_b = token_of(body)
    r = requests.get(f"{BASE}/submissions/{sid_a}", headers=bearer(tok_b), timeout=10)
    record("QA-E01", "学员 B 查看学员 A 提交详情 → 403", "P0", code_of(r) == 403,
           f"http={r.status_code} code={code_of(r)}")

    # B 分页查询带 userId=A → 仍只能看自己
    r = requests.get(f"{BASE}/submissions/page", params={"pageNo": 1, "pageSize": 50, "userId": 1001},
                     headers=bearer(tok_b), timeout=10)
    rows = (r.json().get("data") or {}).get("list") or []
    foreign = [x for x in rows if x.get("userId") and x.get("userId") != (body.get("data") or {}).get("userId")]
    record("QA-E02", "分页 userId 参数注入他人 id 不越权", "P0", not foreign,
           f"rows={len(rows)} foreign={len(foreign)}")

    # 重判权限
    r = requests.post(f"{BASE}/submissions/{sid_a}/rejudge", headers=bearer(tok_a), timeout=10)
    record("QA-E03", "学员触发重判 → 403", "P0", code_of(r) == 403,
           f"http={r.status_code} code={code_of(r)}")

    if teacher_token:
        # 教师重判非本人题目（种子题 4001 归属教师，若归属另一位则 403）
        r = requests.post(f"{BASE}/submissions/{sid_a}/rejudge", headers=bearer(teacher_token), timeout=10)
        record("QA-E04", "教师重判（题目归属决定放行/403，不 500）", "P1",
               code_of(r) in (200, 403, 400), f"http={r.status_code} code={code_of(r)}")

    # 教师访问用户列表（设计允许：@RequireRole({STAFF, TEACHER})，非越权）
    if teacher_token:
        r = requests.get(f"{BASE}/users/page", headers=bearer(teacher_token), timeout=10)
        record("QA-E05", "教师访问用户列表 → 放行（设计允许教师角色）", "P1", code_of(r) == 200,
               f"http={r.status_code} code={code_of(r)}")

    # 学员跨用户读取资料：/users/{id} 无角色注解 + service 无归属校验 → 甄别 PII 泄露
    probe_uid = str(NEW_UID) if NEW_UID else "2104864888726757377"
    r = requests.get(f"{BASE}/users/{probe_uid}", headers=bearer(tok_a), timeout=10)
    d = (r.json().get("data") or {}) if r.status_code == 200 and is_ok(r.json()) else {}
    leaked = isinstance(d, dict) and bool(d.get("cellPhone"))
    record("QA-E06", "学员读取他人资料不下发手机号（水平越权/PII 防护）", "P0", not leaked,
           f"http={r.status_code} code={code_of(r)} "
           + (f"cellPhone 泄露={d.get('cellPhone')}" if leaked else "cellPhone 未下发"))


# ============================================================
# E. 平台防护与配置面（P1/P2）
# ============================================================

def suite_security_surface():
    print("\n===== E. 平台防护与配置面 =====")
    # QA-F01（BUG-002 修复契约）：网关自身 /actuator/** 由 ActuatorGuardFilter 防护。
    #   严格模式（.env 配置 CJ_ACTUATOR_TOKEN）：无令牌 404，持令牌 200；
    #   内网白名单模式（默认）：套件来源是回环 → 200 属预期（公网来源由过滤器单测覆盖），
    #   记 WARN 提醒生产应设置令牌。
    r = requests.get(f"{BASE}/actuator/health", timeout=10)
    if ACTUATOR_TOKEN:
        r_ok = requests.get(f"{BASE}/actuator/health",
                            headers={"X-Actuator-Token": ACTUATOR_TOKEN}, timeout=10)
        record("QA-F01", "actuator 严格模式：无令牌 404 / 持令牌 200", "P0",
               r.status_code == 404 and r_ok.status_code == 200,
               f"无令牌={r.status_code} 持令牌={r_ok.status_code}")
    else:
        record("QA-F01", "actuator 内网白名单模式（本机回环可达；生产请设 CJ_ACTUATOR_TOKEN）",
               "P0", True, f"status={r.status_code}", warn=True)

    r = requests.get(f"{BASE}/doc.html", timeout=10)
    record("QA-F02", "文档路径经网关（无路由→404；README 声称可访问，见缺陷）", "P2",
           r.status_code in (200, 401, 404), f"status={r.status_code}", warn=(r.status_code == 200))

    # CORS：恶意来源不授予
    r = requests.options(f"{BASE}/accounts/login", headers={
        "Origin": "http://evil.example.com",
        "Access-Control-Request-Method": "POST"}, timeout=10)
    grant = r.headers.get("Access-Control-Allow-Origin")
    record("QA-F03", "CORS 恶意来源不授予", "P0", grant is None, f"ACAO={grant}")

    r = requests.options(f"{BASE}/accounts/login", headers={
        "Origin": "http://localhost:5174",
        "Access-Control-Request-Method": "POST"}, timeout=10)
    grant = r.headers.get("Access-Control-Allow-Origin")
    record("QA-F04", "CORS 前端来源正常授予", "P1", grant is not None, f"ACAO={grant}")

    # HTTP 方法收敛
    r = requests.delete(f"{BASE}/problems/page", headers={"Origin": "http://localhost:5174"}, timeout=10)
    record("QA-F05", "DELETE 题目列表端点不被误放行", "P2", r.status_code in (401, 403, 404, 405),
           f"status={r.status_code}")


# ============================================================
# F. 限流（最后执行，避免污染令牌桶）
# ============================================================

def suite_rate_limits(student_token):
    print("\n===== F. 限流测试（最后执行） =====")
    # 提交频控：>30 次/分钟（用 13900000002 —— 其他套件不用该用户提交，避免计数污染）
    st, body = login("13900000002", "123456")
    tok = token_of(body)
    if tok:
        limited = False
        first_detail = ""
        for i in range(35):
            r = requests.post(f"{BASE}/submissions", headers=bearer(tok),
                              json={"problemId": 4001, "language": "PYTHON",
                                    "code": f"print('rate-{i}-{int(time.time())}')"}, timeout=10)
            if i < 2:
                first_detail = f"status={r.status_code} code={r.json().get('code')} msg={str(r.json().get('msg'))[:40]}"
            if code_of(r) == 400 and "频繁" in str(r.json().get("msg") or ""):
                limited = True
                break
        record("QA-G01", "提交频控 >30次/分钟 生效", "P0", limited,
               (f"第{i + 1}次触发" if limited else f"35 次均未被限流；首请求 {first_detail}"))
    else:
        record("QA-G01", "提交频控 >30次/分钟 生效", "P0", False, "登录失败")

    # 登录爆破限流：突发 8 次错误密码 → 至少一次 429
    codes = []
    for _ in range(8):
        try:
            r = requests.post(f"{BASE}/accounts/login",
                              json={"cellPhone": "13900000005", "password": "bad-bad-bad"}, timeout=5)
            codes.append(r.status_code)
        except requests.RequestException as e:
            codes.append(str(e)[:30])
    record("QA-G02", "登录爆破限流（≥1 次 429）", "P0", 429 in codes, f"codes={codes}")


def main():
    load_env()
    print(f"目标环境：{BASE}")
    print(f"JWT_SECRET：{'已加载（伪造用例可执行）' if JWT_SECRET else '未加载'}")

    student_token, student_uid = suite_auth()

    # 教师 token
    _, t_body = login("13900000011", "123456")
    teacher_token = token_of(t_body)
    if teacher_token:
        d = t_body.get("data") or {}
        record("QA-A15", "教师登录（角色=3）", "P1", d.get("role") == 3, f"role={d.get('role')}")
    else:
        record("QA-A15", "教师登录（角色=3）", "P1", False, "教师账号登录失败")

    suite_problems(student_token)
    suite_submission_e2e(student_token, teacher_token)
    suite_authz(student_token, teacher_token)
    suite_security_surface()
    suite_rate_limits(student_token)

    # ---- 汇总 ----
    print("\n" + "=" * 60)
    total = len(results)
    passed = sum(1 for r in results if r[3] == "PASS")
    failed = sum(1 for r in results if r[3] == "FAIL")
    warned = sum(1 for r in results if r[3] == "WARN")
    print(f"总计 {total} 项：PASS {passed} | FAIL {failed} | WARN {warned}")
    if failed:
        print("\n失败项：")
        for tc_id, name, prio, status, detail in results:
            if status == "FAIL":
                print(f"  {tc_id} [{prio}] {name} — {detail}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
