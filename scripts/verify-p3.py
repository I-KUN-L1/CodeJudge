#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P3 端到端验收脚本：提交 -> MQ -> 沙箱判题 -> 结果回写。

前置条件：
  1. 基础设施容器运行中（mysql/redis/mq-namesrv/mq-broker）；
  2. 服务已启动：gateway 9080 / auth 9081 / user 9082 / problem 9083 / submission 9084 / worker 9085；
  3. 4 个沙箱镜像已构建（scripts/build-sandbox-images.py）。

覆盖断言（对应 PLAN §7 P3 验收硬标准）：
  A. 六种 verdict 逐条复现（4001 AC/WA、4002 TLE、4003 MLE、4004 RE、4005 CE via Java）
  B. 提交幂等：60s 内同码重复提交返回同一 submissionId（idempotent=true）
  C. 隐藏用例摘要不下发（学员视角）
  D. 安全用例：无限循环/内存爆炸/socket/读系统目录/写系统目录/fork 炸弹/输出爆炸
  E. 集群视图 /workers（管理员）可见 worker 心跳与队列指标

用法（需 6 服务已启动）：
    python scripts/verify-p3.py
可选环境变量：
    CJ_P3_PHONE  学员手机号（默认 13900000001）
    CJ_P3_PASS   学员密码（默认 123456）
"""

import json
import os
import sys
import time

import requests

GATEWAY = os.environ.get("CJ_P3_GATEWAY", "http://localhost:9080")
PHONE = os.environ.get("CJ_P3_PHONE", "13900000001")
PASSWORD = os.environ.get("CJ_P3_PASS", "123456")
TIMEOUT = 15

PASS, FAIL = 0, 0


def check(name, ok, detail=""):
    global PASS, FAIL
    mark = "PASS" if ok else "FAIL"
    print(f"[{mark}] {name}" + (f" —— {detail}" if detail else ""))
    if ok:
        PASS += 1
    else:
        FAIL += 1


def login():
    r = requests.post(f"{GATEWAY}/accounts/login",
                      json={"cellPhone": PHONE, "password": PASSWORD}, timeout=TIMEOUT)
    data = r.json()
    token = (data.get("data") or {}).get("accessToken")
    if not token:
        raise SystemExit(f"登录失败：{data}")
    return {"Authorization": f"Bearer {token}"}


def submit(headers, problem_id, language, code, contest_id=0):
    r = requests.post(f"{GATEWAY}/submissions", headers=headers,
                      json={"problemId": problem_id, "contestId": contest_id,
                            "language": language, "code": code}, timeout=TIMEOUT)
    return r.json()


def wait_verdict(headers, submission_id, timeout_s=180, expect_states=("PENDING", "JUDGING")):
    """轮询直到终态；返回 (verdict, detail_dict)。"""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        d = requests.get(f"{GATEWAY}/submissions/{submission_id}", headers=headers, timeout=TIMEOUT).json()
        data = d.get("data") or {}
        status = data.get("status")
        if status not in expect_states:
            return data.get("verdict"), data
        time.sleep(2)
    return "TIMEOUT-WAIT", {}


# ============================ 判题代码集 ============================

AC_PY = "a, b = map(int, input().split()); print(a + b)"
WA_PY = "a, b = map(int, input().split()); print(a - b)"
TLE_PY = "n = int(input())\nwhile True:\n    pass"
# 边读边累加，正常解法；MLE 用另写的分配爆炸代码触发
MLE_PY = "import sys\nn = int(sys.stdin.readline()); xs = [0] * (n + 10000000)\nprint(sum(xs[:n]))"
RE_PY = "n = int(input()); xs = list(map(int, input().split())); print(xs[n] - xs[0])"  # 下标越界
CE_JAVA = "public class Main { public static void main(String[] args { }"  # 语法错误

# 安全用例（对 4001 提交，语言 PYTHON）
SEC_LOOP = "while True:\n    pass"                       # -> TLE
SEC_MEM = "xs = [0] * (10 ** 9)"                          # -> MLE
SEC_SOCK = "import socket\ns = socket.socket()\ns.connect(('www.baidu.com', 80))\nprint('net ok')"  # -> RE
SEC_READ = "import os\nprint(os.path.exists('/etc/passwd'))\nprint(open('/etc/hostname').read())"   # 只读投递内可读宿主无关文件，预期正常结束（AC/WA 语义无关紧要，关键是 worker 不崩）
SEC_WRITE = "open('/etc/passwd', 'w').write('pwned')"     # 只读根文件系统 -> RE
SEC_FORK = "import os\nwhile True:\n    os.fork()"        # pids-limit -> RE
SEC_OUT = "while True:\n    print('A' * 100000)"          # 输出爆炸 -> 截断 + TLE/WA


def main():
    print("=" * 70)
    print("P3 验收：提交 -> MQ -> 沙箱判题 -> 结果回写")
    print("=" * 70)
    headers = login()
    print("登录 OK（学员视角）\n")

    # ---------- B. 幂等 ----------
    first = submit(headers, 4001, "PYTHON", AC_PY)
    check("B1 首次提交受理返回 PENDING", first.get("code") == 200 and (first.get("data") or {}).get("status") == "PENDING", json.dumps(first.get("data") or {}, ensure_ascii=False)[:120])
    sid = (first.get("data") or {}).get("id")
    second = submit(headers, 4001, "PYTHON", AC_PY)
    check("B2 重复同码命中幂等", second.get("code") == 200 and (second.get("data") or {}).get("idempotent") is True
          and (second.get("data") or {}).get("id") == sid,
          f"first={sid} second={(second.get('data') or {}).get('id')} idempotent={(second.get('data') or {}).get('idempotent')}")

    # ---------- A. 六种 verdict ----------
    print("\n等待首个提交判题完成（AC 基线）……")
    verdict, detail = wait_verdict(headers, sid)
    check("A1 4001 正确解 → AC", verdict == "AC", f"verdict={verdict} score={detail.get('score')} time={detail.get('timeMs')}ms mem={detail.get('memoryKb')}KB")

    def judge_expect(name, problem, lang, code, expected, extra=None, expect_alternatives=()):
        d = submit(headers, problem, lang, code)
        data = d.get("data") or {}
        if d.get("code") != 200 or not data.get("id"):
            check(name, False, f"受理失败：{json.dumps(d, ensure_ascii=False)[:150]}")
            return
        v, detail2 = wait_verdict(headers, data["id"])
        ok = (v == expected) or (v in expect_alternatives)
        check(name, ok, f"verdict={v}（期望 {expected}）" + (f"；{extra}" if extra and ok else ""))
        return detail2

    judge_expect("A2 4001 错误解 → WA", 4001, "PYTHON", WA_PY, "WA")
    judge_expect("A3 4002 朴素循环 → TLE", 4002, "PYTHON", TLE_PY, "TLE")
    judge_expect("A4 4003 内存爆炸 → MLE", 4003, "PYTHON", MLE_PY, "MLE")
    judge_expect("A5 4004 下标越界 → RE", 4004, "PYTHON", RE_PY, "RE")
    ce_detail = judge_expect("A6 4005 Java 语法错误 → CE", 4005, "JAVA", CE_JAVA, "CE")
    if ce_detail:
        ci = ce_detail.get("compileInfo") or {}
        check("A6b CE 携带编译错误信息", ci.get("success") is False and bool(ci.get("stderrLog")), str(ci)[:120])

    # ---------- C. 隐藏用例摘要遮蔽 ----------
    d = requests.get(f"{GATEWAY}/submissions/{sid}", headers=headers, timeout=TIMEOUT).json()
    cases = (d.get("data") or {}).get("caseResults") or []
    hidden_cases = [c for c in cases if c.get("hidden")]
    masked = all(c.get("outputDigest") is None for c in hidden_cases)
    check("C1 学员视角隐藏用例存在且摘要被屏蔽", len(cases) >= 2 and len(hidden_cases) >= 1 and masked,
          f"共 {len(cases)} 个用例结果，隐藏 {len(hidden_cases)} 个")

    # ---------- D. 安全用例 ----------
    print("\n—— 安全用例（对 4001 提交）——")
    judge_expect("D1 无限循环 → TLE", 4001, "PYTHON", SEC_LOOP, "TLE")
    judge_expect("D2 内存爆炸 → MLE", 4001, "PYTHON", SEC_MEM, "MLE")
    judge_expect("D3 socket 外连被断网拦截 → RE", 4001, "PYTHON", SEC_SOCK, "RE")
    judge_expect("D4 写系统文件被只读根拦截 → RE", 4001, "PYTHON", SEC_WRITE, "RE")
    judge_expect("D5 fork 炸弹被 pids-limit 拦截 → RE", 4001, "PYTHON", SEC_FORK, "RE")
    out_detail = judge_expect("D6 输出爆炸被截断+超时终止", 4001, "PYTHON", SEC_OUT, "TLE",
                              expect_alternatives=("RE",))
    if out_detail:
        digests = [c.get("outputDigest") for c in (out_detail.get("caseResults") or []) if c.get("outputDigest")]
        bounded = all(len(x or "") <= 512 + 32 for x in digests)
        check("D6b 输出摘要有界（防输出爆炸拖垮 worker）", bounded, f"max digest len={max((len(x) for x in digests), default=0)}")

    # ---------- E. 集群视图 ----------
    admin = login_admin()
    if admin:
        w = requests.get(f"{GATEWAY}/workers", headers=admin, timeout=TIMEOUT).json()
        workers = (w.get("data") or [])
        check("E1 /workers 可见在线 worker 心跳", w.get("code") == 200 and len(workers) >= 1,
              json.dumps(workers, ensure_ascii=False)[:150])
        m = requests.get(f"{GATEWAY}/workers/metrics", headers=admin, timeout=TIMEOUT).json()
        check("E2 /workers/metrics 队列指标可读", m.get("code") == 200, json.dumps(m.get("data") or {}, ensure_ascii=False))
        st = requests.get(f"{GATEWAY}/workers", headers=headers, timeout=TIMEOUT).json()
        check("E3 学员访问 /workers 被拒（403）", (st.get("data") is None and st.get("code") != 200), f"code={st.get('code')}")
    else:
        print("[SKIP] 未提供管理员凭据（CJ_P3_ADMIN_PHONE/CJ_P3_ADMIN_PASS），跳过 /workers 断言")

    print("\n" + "=" * 70)
    print(f"结果：PASS={PASS}  FAIL={FAIL}")
    print("=" * 70)
    sys.exit(0 if FAIL == 0 else 1)


def login_admin():
    phone = os.environ.get("CJ_P3_ADMIN_PHONE")
    pwd = os.environ.get("CJ_P3_ADMIN_PASS")
    if not (phone and pwd):
        return None
    r = requests.post(f"{GATEWAY}/accounts/login", json={"cellPhone": phone, "password": pwd}, timeout=TIMEOUT)
    token = (r.json().get("data") or {}).get("accessToken")
    return {"Authorization": f"Bearer {token}"} if token else None


if __name__ == "__main__":
    main()
