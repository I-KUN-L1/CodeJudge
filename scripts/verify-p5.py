#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P5 端到端验收脚本：AI 代码点评（LLM + pgvector RAG + SSE 流式）。

前置条件：
  1. 基础设施容器运行中（mysql / redis / pgvector / mq-*）；
     ⚠️ judge_ai 是 **PostgreSQL**（5433），与其余 5 个 MySQL 库不同；
  2. 8 个服务已启动：gateway 9080 / auth 9081 / user 9082 / problem 9083
     / submission 9084 / worker 9085 / contest 9086 / ai 9087；
  3. 沙箱镜像已构建（scripts/build-sandbox-images.py）—— D/E 段需要真实判题；
  4. 种子数据已灌入（sql/seed.sql）。

覆盖断言（对应 PLAN §7 P5 验收硬标准）：
  A. judge-ai 运行在响应式栈（Netty/9087），健康检查可用
  B. 入口鉴权：无身份头 401、畸形身份头 401（不得 500）
  C. 知识库权限：学员 403 / 教师放行
  D. pgvector 知识库：切片预览、入库、计数、向量检索命中、replace 幂等、清空
  E. 点评上下文与归属：学员仅能点评自己的提交，越权被拒
  F. SSE 契约：事件序列 START→RETRIEVAL→DELTA×N→END、id/seq 单调、增量可拼接
  G. 降级路径：LLM 未配置时 degraded=true + finishReason=DEGRADED，且不报 5xx
  H. 落库与历史：点评落 ai_review（status=1）、history/detail 可查、content 非空
  I. 越权负例：以他人身份点评 → SSE ERROR 403
  J. Last-Event-ID 断线重连：只回放未送达事件、不重新生成（finishReason=REPLAYED）
  K. 网关端到端：经 9080 的 /ai/** 路由完成一次完整流式点评
  L. 边界输入：submissionId 缺失/不存在 → 结构化 ERROR 事件而非 5xx

用法（需 8 服务已启动，**耗时约 3~8 分钟**，取决于是否配置 LLM）：
    python scripts/verify-p5.py

⚠️ 关于身份头（最容易踩的坑）：
   judge-ai **不解析 JWT**，它只信任网关注入的 `user-info` / `role-info`
   （网关验签后会先**剥离**客户端伪造的同名头再注入，防伪造）。因此：
     · 经网关的调用（K 段）用 `Authorization: Bearer <token>`；
     · 直连 9087 的调用（其余各段）必须自行还原 `user-info` / `role-info` ——
       本脚本从登录返回的 JWT payload 解 `userId` / `roleId` claim 得到（与网关同源）。
   直连时若不带这两个头，judge-ai 一律返回 401 —— 这是**设计**，不是缺陷。

可选环境变量：
    CJ_P5_GATEWAY          网关地址（默认 http://localhost:9080）
    CJ_P5_AI               judge-ai 直连地址（默认 http://localhost:9087）
    CJ_P5_STUDENT1_PHONE   学员一手机号（默认 13900000001）
    CJ_P5_STUDENT2_PHONE   学员二手机号（默认 13900000002）
    CJ_P5_TEACHER_PHONE    教师手机号（默认 13900000011）
    CJ_P5_PASS             密码（默认 123456）
    CJ_P5_PROBLEM_ID       用于点评的题目 id（默认 4001）
    CJ_P5_SKIP_SLOW        =1 时跳过需要真实判题的 E/F/G/H/I/J 段

⚠️ 关于 LLM：未配置 CJ_LLM_API_KEY 时点评走**结构化降级**路径。
   本脚本会自动探测并断言对应分支，不把「未配置 LLM」判为失败。
"""

import base64
import json
import os
import sys
import threading
import time

import requests

GATEWAY = os.environ.get("CJ_P5_GATEWAY", "http://localhost:9080")
AI_DIRECT = os.environ.get("CJ_P5_AI", "http://localhost:9087")
STU1_PHONE = os.environ.get("CJ_P5_STUDENT1_PHONE", "13900000001")
STU2_PHONE = os.environ.get("CJ_P5_STUDENT2_PHONE", "13900000002")
TEACHER_PHONE = os.environ.get("CJ_P5_TEACHER_PHONE", "13900000011")
PASSWORD = os.environ.get("CJ_P5_PASS", "123456")
PROBLEM_ID = int(os.environ.get("CJ_P5_PROBLEM_ID", "4001"))
SKIP_SLOW = os.environ.get("CJ_P5_SKIP_SLOW") == "1"
TIMEOUT = 20

NO_PROXY = "localhost,127.0.0.1"
os.environ["NO_PROXY"] = NO_PROXY
os.environ["no_proxy"] = NO_PROXY

PASS, FAIL, SKIP = 0, 0, 0


def check(name, ok, detail=""):
    global PASS, FAIL
    mark = "PASS" if ok else "FAIL"
    print(f"[{mark}] {name}" + (f" —— {detail}" if detail else ""))
    if ok:
        PASS += 1
    else:
        FAIL += 1


def skip(name, detail=""):
    global SKIP
    print(f"[SKIP] {name}" + (f" —— {detail}" if detail else ""))
    SKIP += 1


def section(title):
    print(f"\n{'=' * 78}\n{title}\n{'=' * 78}")


# ============================================================================
# HTTP / 登录
# ============================================================================

def _jwt_claims(token):
    """解出 JWT 的 payload（**不验签** —— 这里只需还原网关会注入的身份，无安全性诉求）。"""
    try:
        seg = token.split(".")[1]
        seg += "=" * (-len(seg) % 4)          # base64url 补齐 padding
        return json.loads(base64.urlsafe_b64decode(seg.encode("ascii")).decode("utf-8"))
    except Exception:
        return {}


def direct_headers(token):
    """
    构造「绕过网关直连 judge-ai」所需的身份头。

    judge-ai **不解析 JWT**：它只信任网关注入的 `user-info` / `role-info`
    （网关在鉴权过滤器里会先**剥离**客户端自带的同名头再注入，防伪造）。
    因此直连 9087 时必须自行还原这两个头，取值与网关注入同源 ——
    JWT 的 `userId` / `roleId` claim（见 gateway 的 JwtUtils.parseIdentity）。
    """
    claims = _jwt_claims(token)
    uid, role = claims.get("userId"), claims.get("roleId")
    if uid is None or role is None:
        print(f"[WARN] JWT 缺少 userId/roleId claim（userId={uid} roleId={role}），"
              f"直连段将无法通过鉴权")
    return {"user-info": str(uid), "role-info": str(role)}


def login(phone, password=PASSWORD):
    """登录，返回 (gateway_headers, direct_headers)；失败返回 (None, None)。

    · gateway_headers：`Authorization: Bearer <token>`，用于经 9080 的调用（K 段）；
    · direct_headers ：`user-info` / `role-info`，用于直连 9087 的各段（模拟网关注入）。
    """
    r = requests.post(f"{GATEWAY}/accounts/login",
                      json={"cellPhone": phone, "password": password}, timeout=TIMEOUT)
    data = r.json()
    token = (data.get("data") or {}).get("accessToken")
    if not token:
        return None, None
    return {"Authorization": f"Bearer {token}"}, direct_headers(token)


def submit(headers, problem_id, language, code, contest_id=0):
    r = requests.post(f"{GATEWAY}/submissions", headers=headers,
                      json={"problemId": problem_id, "contestId": contest_id,
                            "language": language, "code": code}, timeout=TIMEOUT)
    return r.json()


def wait_verdict(headers, submission_id, timeout_s=180):
    """轮询直到终态；返回 (verdict, detail_dict)。"""
    deadline = time.time() + timeout_s
    last = {}
    while time.time() < deadline:
        d = requests.get(f"{GATEWAY}/submissions/{submission_id}",
                         headers=headers, timeout=TIMEOUT).json()
        data = d.get("data") or {}
        last = data
        if data.get("status") not in ("PENDING", "JUDGING", None):
            return data.get("verdict"), data
        time.sleep(2)
    return "TIMEOUT-WAIT", last


AC_PY = "a, b = map(int, input().split())\nprint(a + b)"
WA_PY = "a, b = map(int, input().split())\nprint(a - b)"


# ============================================================================
# SSE 解析
# ============================================================================

def parse_sse_events(lines):
    """把 SSE 行序列聚合成事件列表（与前端 aiReviewStream.js 的解析规则一致）。"""
    events, buf = [], []

    def flush():
        if not buf:
            return
        eid, evt, data, comments = None, "message", [], []
        for ln in buf:
            if not ln:
                continue
            if ln.startswith(":"):
                comments.append(ln[1:].strip())
                continue
            if ":" in ln:
                field, value = ln.split(":", 1)
                if value.startswith(" "):
                    value = value[1:]
            else:
                field, value = ln, ""
            if field == "id":
                eid = value
            elif field == "event":
                evt = value
            elif field == "data":
                data.append(value)
        events.append({"id": eid, "event": evt,
                       "data": "\n".join(data), "comments": comments})
        buf.clear()

    for ln in lines:
        if ln == "":
            flush()
        else:
            buf.append(ln.rstrip("\r"))
    flush()
    return events


def sse_request(url, headers, payload=None, method="POST", timeout=240, max_events=None):
    """
    消费一次 SSE 请求，返回 (http_status, content_type, events, text_snippet)。

    max_events 非空时，收满该数量事件后主动关闭连接 ——
    用于模拟「客户端中途断开」，验证服务端的取消落库路径。
    """
    events, ctype, status = [], "", 0
    try:
        if method == "POST":
            resp = requests.post(url, headers=headers, json=payload,
                                 stream=True, timeout=(10, timeout))
        else:
            resp = requests.get(url, headers=headers, stream=True, timeout=(10, timeout))
    except Exception as e:
        return 0, "", [], f"连接失败：{e}"

    with resp:
        status = resp.status_code
        ctype = resp.headers.get("content-type", "")
        if "text/event-stream" not in ctype:
            body = resp.text[:400]
            return status, ctype, [], body

        raw_lines = []
        try:
            for ln in resp.iter_lines(decode_unicode=True):
                if ln is None:
                    continue
                raw_lines.append(ln)
                if max_events and ln == "":
                    if len([e for e in parse_sse_events(raw_lines) if e["data"]]) >= max_events:
                        break
        except Exception as e:
            # 主动关闭会抛异常，属预期
            if not max_events:
                return status, ctype, parse_sse_events(raw_lines), f"读流中断：{e}"
        events = parse_sse_events(raw_lines)
    return status, ctype, events, ""


def event_types(events):
    """从事件里取出业务 type 序列（忽略纯心跳块）"""
    out = []
    for e in events:
        if not e["data"]:
            continue
        try:
            out.append(json.loads(e["data"]).get("type"))
        except Exception:
            out.append("__BAD_JSON__")
    return out


def vo_list(events):
    out = []
    for e in events:
        if not e["data"]:
            continue
        try:
            out.append(json.loads(e["data"]))
        except Exception:
            pass
    return out


def joined_deltas(events):
    return "".join(v.get("content") or "" for v in vo_list(events) if v.get("type") == "DELTA")


# ============================================================================
# A. 服务栈与健康
# ============================================================================

def sec_a():
    section("A. judge-ai 运行在响应式栈（Netty / 9087）")
    try:
        r = requests.get(f"{AI_DIRECT}/actuator/health", timeout=30)
        body = r.json()
        check("A1 judge-ai /actuator/health 可达", r.status_code == 200,
              f"HTTP {r.status_code}")
        check("A2 健康状态为 UP（含 PG 连通）", body.get("status") == "UP",
              f"status={body.get('status')}  detail={json.dumps(body.get('components', {}), ensure_ascii=False)[:200]}")
        # 响应式栈的证据：actuator 响应头不应有 Servlet 容器特征
        server = r.headers.get("server", "")
        check("A3 未暴露 Tomcat（响应式 Netty 栈）", "tomcat" not in server.lower(),
              f"server={server or '(未设置)'}")
    except Exception as e:
        check("A1 judge-ai /actuator/health 可达", False, str(e))
        check("A2 健康状态为 UP", False, "见 A1")
        check("A3 未暴露 Tomcat", False, "见 A1")


# ============================================================================
# B. 入口鉴权
# ============================================================================

def sec_b():
    section("B. 入口鉴权（身份头解析不得 500）")
    url = f"{AI_DIRECT}/ai/review/stream?submissionId=1"

    r = requests.get(url, timeout=TIMEOUT)
    try:
        j = r.json()
    except Exception:
        j = {}
    check("B1 无身份头 → 401", j.get("code") == 401, f"HTTP {r.status_code} body={j}")

    r = requests.get(url, headers={"user-info": "abc", "role-info": "xyz"}, timeout=TIMEOUT)
    try:
        j = r.json()
    except Exception:
        j = {}
    check("B2 畸形身份头 → 401（不得 500）", j.get("code") == 401,
          f"HTTP {r.status_code} body={j}")

    r = requests.get(url, headers={"role-info": "2"}, timeout=TIMEOUT)
    try:
        j = r.json()
    except Exception:
        j = {}
    check("B3 只有 role 无 user → 401", j.get("code") == 401, f"body={j}")


# ============================================================================
# C. 知识库权限
# ============================================================================

def sec_c(stu1_headers, teacher_headers):
    section("C. 知识库权限（仅教师/管理员）")
    r = requests.get(f"{AI_DIRECT}/ai/knowledge/count", headers=stu1_headers, timeout=TIMEOUT)
    check("C1 学员访问知识库 → 403", r.status_code == 200 and r.json().get("code") == 403,
          f"body={r.text[:160]}")

    if teacher_headers is None:
        skip("C2 教师访问知识库放行", "教师账号不可用")
        return False
    r = requests.get(f"{AI_DIRECT}/ai/knowledge/count", headers=teacher_headers, timeout=TIMEOUT)
    ok = r.status_code == 200 and r.json().get("code") == 200
    check("C2 教师访问知识库放行", ok, f"body={r.text[:200]}")
    return ok


# ============================================================================
# D. pgvector 知识库
# ============================================================================

KNOW_TITLE = "整型溢出的典型表现（P5 验收样例）"
KNOW_BODY = (
    "当题目要求对两个大整数求和时，若使用 32 位整型（Java 的 int、C++ 的 int）"
    "保存中间结果，两个接近 2^31 的数相加会发生有符号整型溢出，"
    "结果变成负数，从而在最大取值附近的用例上得到错误答案（WA）。\n"
    "排查方式：检查所有中间结果的取值范围上界，与所选类型的最大值比较。\n"
    "修改方式：改用 64 位整型（Java 的 long、C++ 的 long long）承载中间结果。"
)


def sec_d(teacher_headers):
    section("D. pgvector 知识库（切片 / 入库 / 检索 / 幂等）")
    if teacher_headers is None:
        for i in range(1, 8):
            skip(f"D{i} 知识库断言", "教师账号不可用")
        return

    # D1 切片预览
    r = requests.post(f"{AI_DIRECT}/ai/knowledge/preview", headers=teacher_headers,
                      json={"content": KNOW_BODY}, timeout=TIMEOUT)
    prev = r.json().get("data") or {}
    check("D1 切片预览返回非空切片", r.status_code == 200 and (prev.get("chunkCount") or 0) >= 1,
          f"chunkCount={prev.get('chunkCount')}")

    # D2 清空后入库（保证可重复执行）
    requests.delete(f"{AI_DIRECT}/ai/knowledge", headers=teacher_headers,
                    params={"problemId": PROBLEM_ID}, timeout=TIMEOUT)
    r = requests.post(f"{AI_DIRECT}/ai/knowledge/upload", headers=teacher_headers,
                      json={"problemId": PROBLEM_ID, "sourceType": "ERROR_PATTERN",
                            "title": KNOW_TITLE, "content": KNOW_BODY, "replace": True},
                      timeout=60)
    up = r.json().get("data") or {}
    check("D2 知识入库成功且切片数 ≥1", r.status_code == 200 and (up.get("chunks") or 0) >= 1,
          f"chunks={up.get('chunks')} total={up.get('total')}")
    first_total = up.get("total")

    # D3 重复入库 + replace=true → 总数不翻倍（幂等）
    r = requests.post(f"{AI_DIRECT}/ai/knowledge/upload", headers=teacher_headers,
                      json={"problemId": PROBLEM_ID, "sourceType": "ERROR_PATTERN",
                            "title": KNOW_TITLE, "content": KNOW_BODY, "replace": True},
                      timeout=60)
    up2 = r.json().get("data") or {}
    check("D3 replace=true 重复入库不使切片翻倍",
          up2.get("total") is not None and first_total is not None
          and abs((up2.get("total") or 0) - (first_total or 0)) <= 1,
          f"first={first_total} second={up2.get('total')}")

    # D4 向量检索命中且相似度合理
    r = requests.post(f"{AI_DIRECT}/ai/knowledge/search", headers=teacher_headers,
                      json={"query": "整型溢出导致答案错误怎么改", "problemId": PROBLEM_ID, "topK": 4},
                      timeout=60)
    hits = r.json().get("data") or []
    check("D4 向量检索命中 ≥1 条", r.status_code == 200 and len(hits) >= 1, f"hits={len(hits)}")
    if hits:
        top = hits[0]
        check("D5 命中标题与语义相关", KNOW_TITLE[:8] in (top.get("title") or ""),
              f"title={top.get('title')} score={top.get('score')}")
        check("D6 相似度为 0~1 区间（余弦）",
              0.0 - 1e-6 <= float(top.get("score") or 0) <= 1.0 + 1e-6,
              f"score={top.get('score')}")
    else:
        skip("D5 命中标题与语义相关", "无命中")
        skip("D6 相似度为 0~1 区间", "无命中")

    # D7 计数端点
    r = requests.get(f"{AI_DIRECT}/ai/knowledge/count", headers=teacher_headers, timeout=TIMEOUT)
    d = r.json().get("data") or {}
    check("D7 知识库计数含切片数", (d.get("knowledgeChunks") or 0) >= 1,
          f"knowledgeChunks={d.get('knowledgeChunks')} reviews={d.get('reviews')}")


# ============================================================================
# E/F/G/H/I/J/K/L. 点评主链路
# ============================================================================

def prepare_submission(stu1_headers, code=AC_PY):
    """提交一段代码并等判题结束，返回 (submissionId, verdict)。"""
    r = submit(stu1_headers, PROBLEM_ID, "PYTHON", code)
    sid = (r.get("data") or {}).get("id") or (r.get("data") or {}).get("submissionId")
    if not sid:
        return None, f"提交失败：{r}"
    verdict, detail = wait_verdict(stu1_headers, sid)
    return sid, verdict


def sec_efghijk(stu1_headers, stu2_headers, teacher_headers, stu1_gateway_headers=None):
    section("E/F/G/H. 点评主链路（SSE 契约 + 降级 + 落库）")

    if SKIP_SLOW:
        for i in range(1, 9):
            skip(f"E/F/G/H 断言 {i}", "CJ_P5_SKIP_SLOW=1")
        return None

    # ⚠️ 提交与轮询走**网关**（/submissions 需要 Authorization 头，网关不认 user-info），
    # 而后续点评走**直连 9087**（需要 user-info/role-info）。两套头不可混用。
    sid, verdict = prepare_submission(stu1_gateway_headers or stu1_headers)
    if not sid:
        check("E1 准备一条已判题提交", False, verdict)
        for i in range(2, 9):
            skip(f"E/F/G/H 断言 {i}", "无可用提交")
        return None
    check("E1 准备一条已判题提交", True, f"submissionId={sid} verdict={verdict}")

    # ---- 归属：越权负例（I 段前置） ----
    if stu2_headers:
        status, ctype, events, snippet = sse_request(
            f"{AI_DIRECT}/ai/review/stream", stu2_headers,
            {"submissionId": sid, "reviewType": 1}, timeout=60)
        vos = vo_list(events)
        err = next((v for v in vos if v.get("type") == "ERROR"), None)
        check("I1 学员点评他人提交 → SSE ERROR 403",
              status == 200 and "text/event-stream" in ctype
              and err is not None and err.get("code") == 403,
              f"HTTP {status} ctype={ctype} err={err} snippet={snippet[:120]}")
    else:
        skip("I1 学员点评他人提交 → SSE ERROR 403", "学员二账号不可用")

    # ---- SSE 主契约（E/F） ----
    t0 = time.time()
    status, ctype, events, snippet = sse_request(
        f"{AI_DIRECT}/ai/review/stream", stu1_headers,
        {"submissionId": sid, "reviewType": 1}, timeout=300)
    elapsed = time.time() - t0

    check("E2 SSE 返回 200 + text/event-stream",
          status == 200 and "text/event-stream" in ctype,
          f"HTTP {status} ctype={ctype} snippet={snippet[:160]}")
    types = event_types(events)
    check("E3 事件序列以 START 开头", types and types[0] == "START", f"types={types[:6]}")
    check("E4 含 RETRIEVAL 事件（RAG 检索阶段）", "RETRIEVAL" in types, f"types={types[:8]}")
    check("E5 含 ≥1 个 DELTA 增量事件", types.count("DELTA") >= 1,
          f"DELTA×{types.count('DELTA')}（耗时 {elapsed:.1f}s）")
    check("E6 事件序列以 END 收尾", types and types[-1] == "END", f"types尾部={types[-3:]}")

    vos = vo_list(events)
    start = next((v for v in vos if v.get("type") == "START"), {})
    end = next((v for v in vos if v.get("type") == "END"), {})
    deltas = [v for v in vos if v.get("type") == "DELTA"]
    body = joined_deltas(events)

    check("E7 START 携带 reviewId（已落库）", start.get("reviewId") is not None,
          f"reviewId={start.get('reviewId')}")
    check("E8 START 携带 model", bool(start.get("model")), f"model={start.get('model')}")
    check("E9 增量正文非空", len(body.strip()) > 0, f"累计 {len(body)} 字符")

    # seq / id 单调
    # ⚠️ all() 对**空序列**返回 True —— 若不校验样本数，零事件时这两条会「假绿」，
    # 把「SSE 根本没跑起来」误报成「单调性正确」。故必须带 len >= 2 前置。
    seqs = [v.get("seq") for v in vos if v.get("seq") is not None]
    ids = [int(e["id"]) for e in events if e.get("id") not in (None, "")]
    check("F1 seq 单调严格递增", len(seqs) >= 2 and all(b > a for a, b in zip(seqs, seqs[1:])),
          f"seqs={seqs[:10]}...（样本 {len(seqs)}）")
    check("F2 SSE id 单调严格递增", len(ids) >= 2 and all(b > a for a, b in zip(ids, ids[1:])),
          f"ids={ids[:10]}...（样本 {len(ids)}）")

    # 降级分支（G）
    degraded = bool(start.get("degraded"))
    if degraded:
        check("G1 未配置 LLM → START.degraded=true", True, "检测到降级模式")
        check("G2 降级时 END.finishReason=DEGRADED", end.get("finishReason") == "DEGRADED",
              f"finishReason={end.get('finishReason')}")
        check("G3 降级仍有结构化正文（非空且含结论章节）",
              "## 结论" in body or "结论" in body, f"前 60 字：{body[:60]!r}")
        check("G4 降级不返回 5xx（HTTP 200）", status == 200, f"HTTP {status}")
    else:
        # 注意：start 为空（一个事件都没收到）时，不能判为「非降级链路通过」——
        # 那只是"没有出现 degraded=true"的假绿。必须要求 START 事件真实存在。
        check("G1 已配置 LLM → START.degraded 为 false",
              bool(start) and not degraded, f"model={start.get('model')}")
        check("G2 非降级时 END.finishReason=STOP", end.get("finishReason") == "STOP",
              f"finishReason={end.get('finishReason')}")
        check("G3 AI 正文非空", len(body.strip()) > 20, f"{len(body)} 字符")
        check("G4 未返回 5xx（HTTP 200）", bool(start) and status == 200,
              f"HTTP {status} events={len(vos)}")

    # ---- 落库与历史（H） ----
    rid = start.get("reviewId")
    if rid is not None:
        r = requests.get(f"{AI_DIRECT}/ai/review/detail/{rid}", headers=stu1_headers, timeout=TIMEOUT)
        d = (r.json().get("data") or {})
        check("H1 点评详情可查（status=1 已完成）", d.get("status") == 1,
              f"status={d.get('status')} id={d.get('id')}")
        check("H2 详情正文与流式内容一致（非空）",
              (d.get("content") or "").strip() != "", f"{len(d.get('content') or '')} 字符")
        check("H3 详情回填 submissionId/problemId",
              d.get("submissionId") == sid and d.get("problemId") == PROBLEM_ID,
              f"submissionId={d.get('submissionId')} problemId={d.get('problemId')}")
    else:
        for i in range(1, 4):
            skip(f"H{i} 落库断言", "START 未携带 reviewId")

    r = requests.get(f"{AI_DIRECT}/ai/review/{sid}", headers=stu1_headers, timeout=TIMEOUT)
    hist = r.json().get("data") or []
    check("H4 点评历史列出 ≥1 条", len(hist) >= 1, f"count={len(hist)}")

    if stu2_headers:
        r = requests.get(f"{AI_DIRECT}/ai/review/{sid}", headers=stu2_headers, timeout=TIMEOUT)
        check("I2 学员查他人点评历史 → 403", r.json().get("code") == 403,
              f"body={r.text[:140]}")
        r = requests.get(f"{AI_DIRECT}/ai/review/detail/{rid}" if rid else f"{AI_DIRECT}/ai/review/detail/1",
                         headers=stu2_headers, timeout=TIMEOUT)
        check("I3 学员查他人点评详情 → 403/404", r.json().get("code") in (403, 404),
              f"code={r.json().get('code')}")
    else:
        skip("I2 学员查他人点评历史 → 403", "学员二账号不可用")
        skip("I3 学员查他人点评详情 → 403/404", "学员二账号不可用")

    # ---- J. Last-Event-ID 回放 ----
    if ids:
        replay_from = ids[-3] if len(ids) >= 3 else ids[0]
        # 用 GET + Last-Event-ID 头重放：不重新生成，服务端只读 Redis 事件缓冲回放。
        # 之所以在**本次生成已结束之后**再重放，是因为缓冲不会在 END 时清空 ——
        # 这正是「客户端断开重连」能续传的前提，也让本断言完全确定性（不依赖时序）。
        status, ctype, snippet = 0, "", ""
        try:
            resp = requests.get(f"{AI_DIRECT}/ai/review/stream",
                                headers={**stu1_headers, "Last-Event-ID": str(replay_from)},
                                params={"submissionId": sid}, stream=True, timeout=(10, 60))
            raw = []
            ctype = resp.headers.get("content-type", "")
            for ln in resp.iter_lines(decode_unicode=True):
                if ln is None:
                    continue
                raw.append(ln)
            revents = parse_sse_events(raw)
            status = resp.status_code
            resp.close()
        except Exception as e:
            revents = []
            snippet = str(e)

        rvos = vo_list(revents)
        rtypes = event_types(revents)
        # 回放语义：**先原样重放**缓冲中 seq > Last-Event-ID 的原始事件
        #（其中可能包含原始 END，其 finishReason 是 STOP/DEGRADED/ERROR），
        # **再追加**一个 finishReason=REPLAYED 的 END 作为「回放结束」标记。
        # 因此必须找「REPLAYED 那个 END」，不能取第一个 END —— 取第一个会拿到原始 END 而误判。
        rend = next((v for v in rvos if v.get("type") == "END"
                     and v.get("finishReason") == "REPLAYED"), {})
        rend_any = next((v for v in rvos if v.get("type") == "END"), {})
        rdelta = sum(1 for t in rtypes if t == "DELTA")
        check("J1 断线重连返回 SSE 流", status == 200 and "text/event-stream" in ctype,
              f"HTTP {status} ctype={ctype}")
        # 空样本时 all() 恒真，故必须同时要求「存在回放结束标记」，否则是假绿。
        # 收尾标记：正常回放 = REPLAYED；缓冲已过期 = REPLAY_EXPIRED。
        # 必须取**最后一个** END —— 中间那个 END 是原样重放的原始事件（finishReason 为 STOP/DEGRADED）。
        replayable = [v for v in rvos if v.get("type") in ("DELTA", "RETRIEVAL")]
        rend_last = next((v for v in reversed(rvos) if v.get("type") == "END"), {})
        check("J2 重连只回放 seq > Last-Event-ID 的事件",
              bool(rvos)
              and all((v.get("seq") or 0) > replay_from for v in replayable)
              and rend_last.get("finishReason") in ("REPLAYED", "REPLAY_EXPIRED"),
              f"Last-Event-ID={replay_from} 回放 DELTA×{rdelta} 样本={len(replayable)} "
              f"收尾={rend_last.get('finishReason')}")
        check("J3 重连以 finishReason=REPLAYED 收尾",
              rend.get("finishReason") == "REPLAYED",
              f"REPLAYED标记={rend.get('finishReason')} 首个END={rend_any.get('finishReason')} "
              f"types={rtypes[:6]}")
    else:
        for i in range(1, 4):
            skip(f"J{i} 断线重连断言", "无事件 id")

    # ---- K. 网关端到端 ----
    section("K. 网关端到端（经 9080 的 /ai/** 路由）")
    # 这一段必须走网关：用 Authorization 头，由网关验签后注入 user-info/role-info，
    # 从而端到端验证「网关 → judge-ai」的完整链路（含 SSE 透传与 response-timeout）。
    gw_headers = stu1_gateway_headers or {}
    try:
        resp = requests.post(f"{GATEWAY}/ai/review/stream", headers=gw_headers,
                             json={"submissionId": sid, "reviewType": 1},
                             stream=True, timeout=(10, 300))
        ctype = resp.headers.get("content-type", "")
        raw = []
        for ln in resp.iter_lines(decode_unicode=True):
            if ln is None:
                continue
            raw.append(ln)
        resp.close()
        gev = parse_sse_events(raw)
        check("K1 网关转发 SSE 成功（200 + text/event-stream）",
              resp.status_code == 200 and "text/event-stream" in ctype,
              f"HTTP {resp.status_code} ctype={ctype}")
        gt = event_types(gev)
        check("K2 网关链路事件序列完整（START…END）",
              gt and gt[0] == "START" and gt[-1] == "END", f"types={gt[:6]}…{gt[-2:]}")
    except Exception as e:
        check("K1 网关转发 SSE 成功", False, str(e))
        check("K2 网关链路事件序列完整", False, "见 K1")

    # ---- L. 边界输入 ----
    section("L. 边界输入")
    status, ctype, levents, snippet = sse_request(
        f"{AI_DIRECT}/ai/review/stream", stu1_headers, {}, timeout=60)
    lv = vo_list(levents)
    lerr = next((v for v in lv if v.get("type") == "ERROR"), None)
    check("L1 submissionId 缺失 → 结构化 ERROR 400",
          lerr is not None and lerr.get("code") == 400, f"err={lerr}")

    status, ctype, levents, snippet = sse_request(
        f"{AI_DIRECT}/ai/review/stream", stu1_headers,
        {"submissionId": 999999999, "reviewType": 1}, timeout=60)
    lv = vo_list(levents)
    lerr = next((v for v in lv if v.get("type") == "ERROR"), None)
    check("L2 提交不存在 → ERROR 事件（404/503 而非 5xx 崩溃）",
          lerr is not None and lerr.get("code") in (404, 503),
          f"err={lerr}")

    return sid


# ============================================================================
# M. 非流式点评
# ============================================================================

def sec_m(stu1_headers, sid):
    section("M. 非流式点评（POST /ai/review）")
    if sid is None:
        skip("M1 非流式点评返回完整正文", "无可用提交（E 段未执行）")
        skip("M2 非流式点评落库", "无可用提交")
        return
    try:
        r = requests.post(f"{AI_DIRECT}/ai/review", headers=stu1_headers,
                          json={"submissionId": sid, "reviewType": 2}, timeout=300)
        d = r.json().get("data") or {}
        check("M1 非流式点评返回完整正文", r.status_code == 200 and r.json().get("code") == 200
              and len((d.get("content") or "").strip()) > 0,
              f"len={len(d.get('content') or '')} model={d.get('model')}")
        check("M2 非流式点评落库（status=1、有 id）",
              d.get("status") == 1 and d.get("id") is not None,
              f"id={d.get('id')} status={d.get('status')}")
    except Exception as e:
        check("M1 非流式点评返回完整正文", False, str(e))
        check("M2 非流式点评落库", False, "见 M1")


# ============================================================================

def main():
    print("P5 验收：AI 代码点评（LLM + pgvector RAG + SSE 流式）")
    print(f"网关 {GATEWAY} / judge-ai 直连 {AI_DIRECT} / 题目 {PROBLEM_ID}")

    # 每个账号取两套头：
    #   · gateway 头（Authorization: Bearer）—— 经 9080 调用（K 段）；
    #   · direct 头（user-info / role-info）—— 直连 9087 时模拟网关注入（A~J、L、M 段）。
    # judge-ai 不解析 JWT，只认网关注入的身份头；直连时不带它 → 一律 401。
    stu1_gw, stu1 = login(STU1_PHONE)
    if stu1 is None:
        raise SystemExit(f"学员一登录失败（{STU1_PHONE}）—— 请先启动 9080~9086 并灌入 sql/seed.sql")
    stu2_gw, stu2 = login(STU2_PHONE)
    teacher_gw, teacher = login(TEACHER_PHONE)

    sec_a()
    sec_b()
    sec_c(stu1, teacher)
    sec_d(teacher)
    sid = sec_efghijk(stu1, stu2, teacher, stu1_gw)
    sec_m(stu1, sid)

    print(f"\n{'=' * 78}")
    print(f"P5 验收结果：PASS={PASS}  FAIL={FAIL}  SKIP={SKIP}")
    print(f"{'=' * 78}")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
