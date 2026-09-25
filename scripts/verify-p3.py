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
     + A1b **主路径时限**（见下）
  B. 提交幂等：60s 内同码重复提交返回同一 submissionId（idempotent=true）
  C. 隐藏用例摘要不下发（学员视角）
  D. 安全用例：无限循环/内存爆炸/socket/读系统目录/写系统目录/fork 炸弹/输出爆炸
  E. 集群视图 /workers（管理员）可见 worker 心跳与队列指标

为什么必须有 A1b「主路径时限」断言：
  2026-09-22 实测捕获「判题首次投递缺失」缺陷 —— `createSubmissionWithTask()` 在
  `judgeTaskMapper.insert()` 之后直接 return，`publishTaskCreated()` **零调用点**，
  任务只能等 `pending-rescue` 兜底。端到端因此稳定停在 28–30s，
  **而当时的验收脚本只断言"最终能出 AC"而不设时限 → 照样全绿通过**。
  功能断言通过 ≠ 设计路径生效；补偿路径会把主路径的彻底失效装饰成"只是有点慢"。

  两侧耗时相距 3× 以上，阈值不会因抖动误判：
    主路径   = afterCommit 直接投递 MQ → 实测 3.4–4.5s
    补偿路径 = fixedDelay 10s + pending-rescue 15s + RETRY_DELAY_LEVEL 5s → 23–33s

用法（需 6 服务已启动）：
    python scripts/verify-p3.py
可选环境变量：
    CJ_P3_PHONE        学员手机号（默认 13900000001）
    CJ_P3_PASS         学员密码（默认 123456）
    CJ_P3_E2E_BUDGET_S 端到端时限预算，秒（默认 15）；判题机高负载时可放宽
    CJ_P3_ADMIN_PHONE  管理员手机号（默认 13800000000）
    CJ_P3_ADMIN_PASS   管理员口令（默认自动读 .env 的 CJ_ADMIN_INIT_PASSWORD）
"""

import json
import os
import pathlib
import sys
import time

import requests

GATEWAY = os.environ.get("CJ_P3_GATEWAY", "http://localhost:9080")
PHONE = os.environ.get("CJ_P3_PHONE", "13900000001")
PASSWORD = os.environ.get("CJ_P3_PASS", "123456")
TIMEOUT = 15


def _env_file_value(key: str) -> str:
    """从仓库根目录的 .env 读取变量（不回显、不硬编码）。

    与 verify-p1-login.py / verify-p6.py 同一套做法：管理员口令已于 2026-09-22 轮换，
    **不再等于种子默认 123456**。若本脚本仍要求人工在命令行导出口令，
    要么 E 段恒被 SKIP（等于没验），要么把明文口令写进 shell 历史
    —— 前者是假绿，后者是泄漏。故改为自动跟随 .env。
    """
    try:
        env_path = pathlib.Path(__file__).resolve().parent.parent / ".env"
        for line in env_path.read_text(encoding="utf-8", errors="replace").splitlines():
            s = line.strip()
            if s.startswith("#") or "=" not in s:
                continue
            k, v = s.split("=", 1)
            if k.strip() == key:
                return v.strip()
    except OSError:
        pass
    return ""


# 优先级：显式环境变量 > .env 的 CJ_ADMIN_INIT_PASSWORD > 历史种子默认值
ADMIN_PHONE = os.environ.get("CJ_P3_ADMIN_PHONE", "13800000000")
ADMIN_PASS = (os.environ.get("CJ_P3_ADMIN_PASS")
              or _env_file_value("CJ_ADMIN_INIT_PASSWORD")
              or "123456")

# 端到端时限预算（秒）：提交受理 → 终态。判据与两侧实测耗时见文件头说明。
# 注意：这不是"性能验收阈值"，而是**路径判据** —— 超限即意味着走了滞留重发补偿路径。
E2E_BUDGET_S = float(os.environ.get("CJ_P3_E2E_BUDGET_S", "15"))

# 本次运行的唯一 nonce，追加到每份被提交的代码末尾。
# 为什么必须有：提交幂等窗是 60s，键含代码内容。若两次运行间隔 < 60s 且代码完全相同，
# 第二次会直接命中幂等、返回**上一次的既有记录** —— 于是
#   · A1b 量到 elapsed≈0.1s（读的是历史快照，根本没判题）；
#   · A 段/D 段"安全用例"全部只回放旧结论，本轮没有真正执行过任何一次判题。
# 表现是"全绿"，实质是整轮空转 —— 属假绿家族（docs/CONTEXT.md §3 第 16 条）。
# 追加的是注释（Python `#` / Java `//`），不改变判题语义，只改变幂等键。
NONCE = str(int(time.time()))


def _nz(code: str, lang: str = "PYTHON") -> str:
    """给代码追加本轮 nonce 注释，使每次运行都产生"新提交"（见 NONCE 说明）。

    注释符按语言选：Java 用 `//`（用 `#` 会让 CE 之外的用例变成另一种语法错误）。
    """
    return code + (f"\n// run {NONCE}" if lang == "JAVA" else f"\n# run {NONCE}")

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


def wait_verdict(headers, submission_id, timeout_s=180, expect_states=("PENDING", "JUDGING"),
                 started_at=None):
    """轮询直到终态；返回 (verdict, detail_dict, elapsed_s)。

    elapsed_s 从 started_at（默认为本次调用起点）算起，调用方应传**提交时刻**，
    否则量到的是"轮询耗时"而不是端到端耗时。
    """
    started_at = time.time() if started_at is None else started_at
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        d = requests.get(f"{GATEWAY}/submissions/{submission_id}", headers=headers, timeout=TIMEOUT).json()
        data = d.get("data") or {}
        status = data.get("status")
        if status not in expect_states:
            return data.get("verdict"), data, time.time() - started_at
        time.sleep(2)
    return "TIMEOUT-WAIT", {}, time.time() - started_at


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
    ac_code = _nz(AC_PY)            # 带 nonce：保证本轮是"真判"，而不是幂等回放历史记录
    t_submit = time.time()          # 端到端计时的起点：提交请求发出前
    first = submit(headers, 4001, "PYTHON", ac_code)
    first_status = (first.get("data") or {}).get("status")
    check("B1 首次提交受理返回 PENDING", first.get("code") == 200 and first_status == "PENDING",
          f"status={first_status} " + json.dumps(first.get("data") or {}, ensure_ascii=False)[:100]
          + ("（命中幂等，返回的是既有记录 —— 本轮未真判）" if first_status != "PENDING" else ""))
    sid = (first.get("data") or {}).get("id")
    second = submit(headers, 4001, "PYTHON", ac_code)
    check("B2 重复同码命中幂等", second.get("code") == 200 and (second.get("data") or {}).get("idempotent") is True
          and (second.get("data") or {}).get("id") == sid,
          f"first={sid} second={(second.get('data') or {}).get('id')} idempotent={(second.get('data') or {}).get('idempotent')}")

    # ---------- A. 六种 verdict ----------
    print("\n等待首个提交判题完成（AC 基线）……")
    verdict, detail, elapsed = wait_verdict(headers, sid, started_at=t_submit)
    check("A1 4001 正确解 → AC", verdict == "AC", f"verdict={verdict} score={detail.get('score')} time={detail.get('timeMs')}ms mem={detail.get('memoryKb')}KB")
    # A1b：路径判据（不是性能阈值）—— 见文件头「为什么必须有 A1b」。
    # 额外要求 first_status == PENDING：否则 elapsed 量的是"读历史记录"的耗时，恒 ≈0，断言退化为恒真。
    slow = elapsed > E2E_BUDGET_S
    check(f"A1b 主路径时限 提交→终态 ≤ {E2E_BUDGET_S:.0f}s",
          first_status == "PENDING" and verdict == "AC" and not slow,
          f"elapsed={elapsed:.1f}s"
          + (f"（超限 {elapsed / E2E_BUDGET_S:.1f}×：疑似走了滞留重发补偿路径，"
             f"即 createSubmissionWithTask 未投递 MQ）" if slow else "")
          + ("（本次提交命中幂等，未真判，时限判据无效）" if first_status != "PENDING" else ""))

    def judge_expect(name, problem, lang, code, expected, extra=None, expect_alternatives=()):
        t0 = time.time()
        d = submit(headers, problem, lang, _nz(code, lang))
        data = d.get("data") or {}
        if d.get("code") != 200 or not data.get("id"):
            check(name, False, f"受理失败：{json.dumps(d, ensure_ascii=False)[:150]}")
            return
        v, detail2, el = wait_verdict(headers, data["id"], started_at=t0)
        ok = (v == expected) or (v in expect_alternatives)
        check(name, ok, f"verdict={v}（期望 {expected}） el={el:.1f}s" + (f"；{extra}" if extra and ok else ""))
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
        # `all(...)` 对空序列返回 True —— 必须同时断言样本非空，否则"零摘要"会被装饰成全绿。
        # 这是 docs/CONTEXT.md §3 第 16 条硬规则（假绿比 FAIL 更危险），P5 首次实跑即修掉 3 处同类。
        bounded = len(digests) >= 1 and all(len(x or "") <= 512 + 32 for x in digests)
        check("D6b 输出摘要有界（防输出爆炸拖垮 worker）", bounded,
              f"样本 {len(digests)} 个，max digest len={max((len(x) for x in digests), default=0)}"
              + ("（无摘要样本，无法判定有界性）" if not digests else ""))

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
        # 凭据已自动跟随 .env（见 ADMIN_PASS），拿不到 token 只可能是口令不对或 admin 账号异常
        # —— 必须 FAIL，不能 SKIP：SKIP 会让「管理员端点整段没验」看起来像通过。
        check("E0 管理员登录（凭据取自 .env 的 CJ_ADMIN_INIT_PASSWORD）",
              False, f"phone={ADMIN_PHONE} 登录失败；请确认该账号存在且口令与 .env 一致")

    print("\n" + "=" * 70)
    print(f"结果：PASS={PASS}  FAIL={FAIL}")
    print("=" * 70)
    sys.exit(0 if FAIL == 0 else 1)


def login_admin():
    if not (ADMIN_PHONE and ADMIN_PASS):
        return None
    r = requests.post(f"{GATEWAY}/accounts/login",
                      json={"cellPhone": ADMIN_PHONE, "password": ADMIN_PASS}, timeout=TIMEOUT)
    token = (r.json().get("data") or {}).get("accessToken")
    return {"Authorization": f"Bearer {token}"} if token else None


if __name__ == "__main__":
    main()
