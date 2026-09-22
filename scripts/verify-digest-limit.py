#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""临时探针：验证 judge_result 摘要截断是否生效（过长输出是否会打死判题）。

背景：2026-09-20 有两条死信，error_msg 为
    Data truncation: Data too long for column 'stderr_digest' / 'output_digest'
当前 JudgeEngine.abbreviate() 已把摘要截到 460 字符 + 后缀，但需要真实跑一次确认。
用法：python scripts/_probe-digest-truncation.py
"""
import json
import re
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://127.0.0.1:9080"
SUBMISSION_METRICS = "http://127.0.0.1:9084/actuator/prometheus"
PHONE, PWD = "13900000001", "123456"
PROBLEM = 4001


def req(method, path, body=None, token=None, timeout=20):
    h = {"Content-Type": "application/json"}
    if token:
        h["Authorization"] = "Bearer " + token
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw[:300]}


def metric(name):
    try:
        with urllib.request.urlopen(SUBMISSION_METRICS, timeout=5) as r:
            body = r.read(512 * 1024).decode("utf-8", "replace")
    except Exception as e:
        return f"(指标不可达: {e})"
    m = re.search(rf"^{name}\{{[^}}]*\}}\s+([-\d.eE+]+)", body, re.M)
    return float(m.group(1)) if m else None


def main():
    before = metric("judge_dead_tasks")
    print(f"提交前  judge_dead_tasks = {before}")

    st, b = req("POST", "/accounts/login", {"cellPhone": PHONE, "password": PWD})
    if b.get("code") != 200:
        print(f"[FATAL] 登录失败 HTTP={st} {b}", file=sys.stderr)
        return 2
    token = b["data"]["accessToken"]
    print("登录 OK")

    nonce = uuid.uuid4().hex
    code = chr(10).join([
        "import sys",
        "data = sys.stdin.read().split()",
        "print(sum(int(x) for x in data))",
        f"print('X' * 3000)  # nonce {nonce}",
        f"sys.stderr.write('E' * 3000 + ' {nonce}')",
    ])
    print(f"提交代码长度 = {len(code)} 字符（stdout/stderr 各约 3000 字符）")

    st, b = req("POST", "/submissions",
                {"problemId": PROBLEM, "language": "PYTHON", "code": code}, token)
    print(f"提交响应 HTTP={st} code={b.get('code')} msg={b.get('msg')}")
    d = b.get("data") or {}
    sid = d.get("id") or d.get("submissionId")
    if not sid:
        print(f"[FATAL] 未取到 submissionId: {json.dumps(b, ensure_ascii=False)[:300]}",
              file=sys.stderr)
        return 2
    print(f"submissionId = {sid}")

    final = {}
    for i in range(40):
        time.sleep(2)
        st, b = req("GET", f"/submissions/{sid}", token=token)
        d = b.get("data") or {}
        stt = d.get("status")
        if stt in ("PENDING", "JUDGING", None) and i < 2:
            continue
        final = d
        if stt not in ("PENDING", "JUDGING", None):
            print(f"  [{i * 2:>3}s] 终态 status={stt}")
            break
        print(f"  [{i * 2:>3}s] status={stt} ...")
    else:
        print("[WARN] 轮询超时，提交可能仍在判题")

    print("\n--- 终态 ---")
    print(f"status   = {final.get('status')}")
    print(f"verdict  = {final.get('verdict')}")
    cases = final.get("cases") or final.get("caseResults") or []
    for c in cases[:5]:
        od = c.get("outputDigest") or ""
        ed = c.get("stderrDigest") or ""
        print(f"  case#{c.get('seq')} verdict={c.get('verdict')} "
              f"outputDigest长度={len(od)} stderrDigest长度={len(ed)}")

    after = metric("judge_dead_tasks")
    print(f"\n提交后  judge_dead_tasks = {after}  (提交前 {before})")
    if isinstance(after, float) and isinstance(before, float):
        if after > before:
            print("❌ 产生新死信 —— 截断仍未生效，缺陷复现！")
            return 1
        print("✅ 未产生新死信；摘要截断生效（列宽 512，abbreviate 截到 465）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
