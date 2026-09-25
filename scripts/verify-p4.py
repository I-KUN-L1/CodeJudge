#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P4 端到端验收脚本：WebSocket 推送 + 竞赛生命周期/封榜 + Redis ZSet 排行榜。

前置条件：
  1. 基础设施容器运行中（mysql / redis / mq-namesrv / mq-broker）；
  2. 7 个服务已启动：gateway 9080 / auth 9081 / user 9082 / problem 9083
     / submission 9084 / worker 9085 / contest 9086；
  3. 4 个沙箱镜像已构建（scripts/build-sandbox-images.py）；
  4. 种子数据已灌入（sql/seed.sql）：contest 5001（已结束）、5002（进行中）。

覆盖断言（对应 PLAN §7 P4 验收硬标准）：
  A. 竞赛生命周期：建赛校验（题目存在/已发布）、状态按时间推导、状态分页
  B. 报名与提交校验：报名幂等、未报名/非本赛题目/已结束竞赛 一律拒绝
  C. Redis ZSet 排行榜：同分罚时少者优先、首次 AC 才计分、ICPC 记法、
     ZSet 原始编码（score = weight×10^7 + (10^7-1-罚时秒)）
  D. 封榜：公众视图冻结、封榜期间成绩不进公开榜、教师 full 视图可见、快照留档、幂等
  E. WebSocket：握手鉴权、越权订阅拒绝、连接即推快照、心跳、进度/终态推送、榜单变更推送
  F. 自动封榜与解封：生命周期扫描自动封榜 → 结束 → 终榜留档 → 公开榜=完整榜
  G. 终榜重建：从提交表回放、结果幂等、权限收紧

用法（需 7 服务已启动，**耗时约 4~5 分钟**，其中 F 段需等待短赛程竞赛自然结束）：
    python scripts/verify-p4.py

可选环境变量：
    CJ_P4_GATEWAY         网关地址（默认 http://localhost:9080）
    CJ_P4_WS              WebSocket 基址（默认 ws://localhost:9080）
    CJ_P4_TEACHER_PHONE   教师手机号（默认 13900000011）
    CJ_P4_STUDENT1_PHONE  学员一手机号（默认 13900000001）
    CJ_P4_STUDENT2_PHONE  学员二手机号（默认 13900000002）
    CJ_P4_STUDENT3_PHONE  学员三手机号（默认 13900000003）
    CJ_P4_PASS            密码（默认 123456）
    CJ_P4_REDIS_PASSWORD  Redis 密码（默认自动读 .env 的 REDIS_PASSWORD）
    CJ_P4_SKIP_SLOW       =1 时跳过 F 段（需等待竞赛结束的慢用例）
"""

import json
import os
import subprocess
import sys
import time
from datetime import datetime, timedelta

import requests
import websocket

GATEWAY = os.environ.get("CJ_P4_GATEWAY", "http://localhost:9080")
WS_BASE = os.environ.get("CJ_P4_WS", "ws://localhost:9080")
TEACHER_PHONE = os.environ.get("CJ_P4_TEACHER_PHONE", "13900000011")
STU1_PHONE = os.environ.get("CJ_P4_STUDENT1_PHONE", "13900000001")
STU2_PHONE = os.environ.get("CJ_P4_STUDENT2_PHONE", "13900000002")
STU3_PHONE = os.environ.get("CJ_P4_STUDENT3_PHONE", "13900000003")
PASSWORD = os.environ.get("CJ_P4_PASS", "123456")
TIMEOUT = 15
SKIP_SLOW = os.environ.get("CJ_P4_SKIP_SLOW") == "1"

# websocket-client 会读 http_proxy 环境变量，本机联调必须绕过代理
os.environ["NO_PROXY"] = "localhost,127.0.0.1"

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


# ============================ HTTP 基础 ============================

def login(phone, password=PASSWORD):
    r = requests.post(f"{GATEWAY}/accounts/login",
                      json={"cellPhone": phone, "password": password}, timeout=TIMEOUT)
    data = r.json()
    token = (data.get("data") or {}).get("accessToken")
    if not token:
        raise SystemExit(f"登录失败 phone={phone}：{json.dumps(data, ensure_ascii=False)}")
    return {"Authorization": f"Bearer {token}"}, token


def submit(headers, problem_id, language, code, contest_id=0):
    r = requests.post(f"{GATEWAY}/submissions", headers=headers,
                      json={"problemId": problem_id, "contestId": contest_id,
                            "language": language, "code": code}, timeout=TIMEOUT)
    return r.json()


TERMINAL = {"SUCCESS", "FAILED"}


def wait_terminal(headers, submission_id, timeout_s=180):
    """轮询直到终态；返回 (verdict, detail)。"""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        d = requests.get(f"{GATEWAY}/submissions/{submission_id}", headers=headers, timeout=TIMEOUT).json()
        data = d.get("data") or {}
        if data.get("status") in TERMINAL:
            return data.get("verdict"), data
        time.sleep(1.5)
    return "TIMEOUT-WAIT", {}


def judge(headers, problem_id, language, code, contest_id=0, expect=None, name=None):
    """提交并等待终态；返回 (是否达预期, 详情)。name 为空则不产生断言。"""
    d = submit(headers, problem_id, language, code, contest_id)
    data = d.get("data") or {}
    if d.get("code") != 200 or not data.get("id"):
        if name:
            check(name, False, f"受理失败：{json.dumps(d, ensure_ascii=False)[:160]}")
        return False, {}
    verdict, detail = wait_terminal(headers, data["id"])
    if name:
        check(name, expect is None or verdict == expect,
              f"verdict={verdict}" + (f"（期望 {expect}）" if expect else ""))
    return (expect is None or verdict == expect), detail


def get_rank(headers, contest_id, full=False, top=None):
    url = f"{GATEWAY}/contests/{contest_id}/rank?full={'true' if full else 'false'}"
    if top:
        url += f"&top={top}"
    r = requests.get(url, headers=headers, timeout=TIMEOUT)
    body = r.json()
    if body.get("code") != 200:
        return None, body
    return body.get("data") or {}, body


def entries_map(rank_vo):
    """entries -> {userId: entry}，便于按人断言。"""
    return {e.get("userId"): e for e in (rank_vo.get("entries") or [])}


def wait_rank(headers, contest_id, predicate, timeout_s=40, full=False):
    """轮询榜单直到 predicate 成立（榜单经 MQ 异步更新，需要等待）。"""
    deadline = time.time() + timeout_s
    last = {}
    while time.time() < deadline:
        vo, _ = get_rank(headers, contest_id, full=full)
        if vo:
            last = vo
            try:
                if predicate(vo):
                    return vo
            except Exception:
                pass
        time.sleep(1.5)
    return last


# ============================ 判题代码集 ============================

AC_PY = "a, b = map(int, input().split()); print(a + b)"
WA_PY = "a, b = map(int, input().split()); print(a - b)"


# ============================ WebSocket 工具 ============================

def ws_connect(path, token=None, timeout=12, params=None):
    """path 可自带查询串（如 full=true）；token 必须作为**独立参数**拼接。

    别写成 f"{path}?token=..." —— 当 path 已含 "?" 时会拼出
    `...?full=true?token=xxx`，第二个问号只是 full 值的一部分，token 根本没传进去，
    表现为「网关 401 握手失败」，看起来像鉴权有问题，实际是 URL 拼错（P4 首轮踩过）。
    """
    query = []
    if params:
        query.append(params)
    if token:
        query.append(f"token={token}")
    url = f"{WS_BASE}{path}"
    if query:
        url += ("&" if "?" in path else "?") + "&".join(query)
    return websocket.create_connection(url, timeout=timeout, suppress_origin=True)


def ws_collect(ws, seconds, stop_types=None, max_frames=200):
    """收集 seconds 秒内的信封；收到 stop_types 之一即提前返回。"""
    out = []
    deadline = time.time() + seconds
    ws.settimeout(1.0)
    while time.time() < deadline and len(out) < max_frames:
        try:
            raw = ws.recv()
        except websocket.WebSocketTimeoutException:
            continue
        except (websocket.WebSocketConnectionClosedException, OSError):
            break
        if not raw:
            break
        try:
            msg = json.loads(raw)
        except ValueError:
            continue
        out.append(msg)
        if stop_types and msg.get("type") in stop_types:
            break
    return out


def ws_expect_reject(path, token=None, timeout=10):
    """订阅应被拒：握手失败 / 收到 ERROR / 未建立 CONNECTED 即断开。
    返回 (是否被拒, 说明)。"""
    try:
        ws = ws_connect(path, token, timeout=timeout)
    except Exception as e:
        return True, f"握手即失败（{type(e).__name__}）"
    try:
        frames = ws_collect(ws, timeout, stop_types=["ERROR", "CONNECTED"])
        for m in frames:
            if m.get("type") == "ERROR":
                return True, f"收到 ERROR：{str(m.get('data'))[:80]}"
            if m.get("type") == "CONNECTED":
                return False, "竟然建立了订阅"
        return True, "未建立订阅即断开"
    finally:
        try:
            ws.close()
        except Exception:
            pass


# ============================ Redis 直读（可选） ============================

def _dotenv(key):
    for path in (".env", "../.env", "../../.env"):
        try:
            with open(path, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if line.startswith(key + "="):
                        return line.split("=", 1)[1].strip()
        except OSError:
            continue
    return None


def redis_cmd(*args):
    """docker exec redis-cli；不可用时返回 None（调用方按 SKIP 处理）。"""
    pw = os.environ.get("CJ_P4_REDIS_PASSWORD") or _dotenv("REDIS_PASSWORD")
    if not pw:
        return None
    try:
        r = subprocess.run(
            ["docker", "exec", "codejudge-redis", "redis-cli", "-a", pw, "--no-auth-warning", *args],
            capture_output=True, text=True, timeout=25)
    except (OSError, subprocess.SubprocessError):
        return None
    return r.stdout if r.returncode == 0 else None


# ============================ 主流程 ============================

def main():
    global PASS, FAIL
    print("=" * 74)
    print("P4 验收：WebSocket 推送 / 竞赛生命周期与封榜 / Redis ZSet 排行榜")
    print("=" * 74)

    teacher, teacher_tok = login(TEACHER_PHONE)
    stu1, stu1_tok = login(STU1_PHONE)
    stu2, stu2_tok = login(STU2_PHONE)
    stu3, stu3_tok = login(STU3_PHONE)
    print("登录 OK：教师 1 / 学员 3\n")

    now = datetime.now()
    iso = lambda dt: dt.strftime("%Y-%m-%dT%H:%M:%S")

    # ==================== A. 建赛与生命周期 ====================
    print("—— A. 竞赛创建与生命周期 ——")

    r = requests.post(f"{GATEWAY}/contests", headers=stu1,
                      json={"title": "越权建赛", "startTime": iso(now), "endTime": iso(now + timedelta(hours=1)),
                            "problems": [{"problemId": 4001}]}, timeout=TIMEOUT).json()
    check("A1 学员建赛被拒（教师/管理员专属）", r.get("code") != 200,
          f"code={r.get('code')} msg={str(r.get('msg'))[:60]}")

    create_payload = {
        "title": "【P4验收】ZSet 榜单与封榜（进行中）",
        "description": "verify-p4.py 自动创建；用于验证同分罚时排序、封榜视图隔离、榜单重建。",
        "rule": "ACM",
        "startTime": iso(now - timedelta(minutes=30)),
        "endTime": iso(now + timedelta(minutes=30)),
        "freezeMinutes": 0,          # 不自动封榜，D 段用 POST /freeze 手动封
        "penaltyMinutes": 20,
        "problems": [
            {"problemId": 4001, "label": "A", "displayOrder": 0, "fullScore": 100},
            {"problemId": 4002, "label": "B", "displayOrder": 1, "fullScore": 100},
        ],
    }
    r = requests.post(f"{GATEWAY}/contests", headers=teacher, json=create_payload, timeout=TIMEOUT).json()
    cid = (r.get("data") or {}).get("id")
    check("A2 教师建赛成功且状态推导为进行中", r.get("code") == 200 and (r.get("data") or {}).get("status") == 1,
          f"contestId={cid} status={(r.get('data') or {}).get('status')}")
    if cid is None:
        # 建赛是所有榜单/封榜/WS 断言的前置。此处失败时后续只会产出一串
        # 「contestId=None」引发的误导性 FAIL（看起来像榜单坏了，其实是建不出来），
        # 因此这里直接终止，让根因唯一地暴露出来。
        print(f"\n[BLOCKER] 建赛失败，后续断言均无意义，提前终止。原始响应："
              f"{json.dumps(r, ensure_ascii=False)[:400]}")
        sys.exit(2)

    detail = (r.get("data") or {})
    probs = detail.get("problems") or []
    check("A3 竞赛详情含题号编排（A/B，满分 100）",
          len(probs) == 2 and [p.get("label") for p in probs] == ["A", "B"]
          and all(p.get("fullScore") == 100 for p in probs),
          json.dumps([(p.get("label"), p.get("problemId"), p.get("fullScore")) for p in probs]))

    # 负例必须断言「拒绝的原因」，不能只断言「失败了」——
    #    否则下游服务不可用也会让这两条看起来通过（P4 验收首轮就踩到过）。
    bad = dict(create_payload, title="不存在的题目", problems=[{"problemId": 999999}])
    r = requests.post(f"{GATEWAY}/contests", headers=teacher, json=bad, timeout=TIMEOUT).json()
    msg4 = str(r.get("msg") or "")
    check("A4 编排不存在的题目被拒（且原因是「题目不存在」）",
          r.get("code") != 200 and "不存在" in msg4, f"msg={msg4[:70]}")

    bad = dict(create_payload, title="未发布题目", problems=[{"problemId": 4006}])
    r = requests.post(f"{GATEWAY}/contests", headers=teacher, json=bad, timeout=TIMEOUT).json()
    msg5 = str(r.get("msg") or "")
    check("A5 编排未发布（草稿）题目被拒（且原因是「题目未发布」）",
          r.get("code") != 200 and "未发布" in msg5, f"msg={msg5[:70]}")

    r = requests.get(f"{GATEWAY}/contests/page?status=1&pageSize=50", headers=teacher, timeout=TIMEOUT).json()
    running_ids = [c.get("id") for c in ((r.get("data") or {}).get("list") or [])]
    r2 = requests.get(f"{GATEWAY}/contests/page?status=2&pageSize=50", headers=teacher, timeout=TIMEOUT).json()
    finished_ids = [c.get("id") for c in ((r2.get("data") or {}).get("list") or [])]
    check("A6 状态分页按时间区间翻译（进行中含新建赛、已结束含 5001）",
          cid in running_ids and 5001 in finished_ids,
          f"进行中={running_ids[:6]} 已结束={finished_ids[:6]}")

    # ==================== B. 报名与提交校验 ====================
    print("\n—— B. 报名与竞赛提交校验 ——")
    r = requests.post(f"{GATEWAY}/contests/{cid}/register", headers=stu1, timeout=TIMEOUT).json()
    check("B1 学员一报名成功", r.get("code") == 200, f"code={r.get('code')}")
    r = requests.post(f"{GATEWAY}/contests/{cid}/register", headers=stu2, timeout=TIMEOUT).json()
    check("B2 学员二报名成功", r.get("code") == 200, f"code={r.get('code')}")
    r = requests.post(f"{GATEWAY}/contests/{cid}/register", headers=stu1, timeout=TIMEOUT).json()
    check("B3 重复报名幂等返回 200", r.get("code") == 200, f"code={r.get('code')}")

    r = submit(stu3, 4001, "PYTHON", AC_PY, cid)
    check("B4 未报名者提交竞赛题被拒", r.get("code") != 200, f"msg={str(r.get('msg'))[:70]}")

    r = submit(stu1, 4003, "PYTHON", AC_PY, cid)   # 4003 不属于本竞赛
    check("B5 提交非本竞赛题目被拒", r.get("code") != 200, f"msg={str(r.get('msg'))[:70]}")

    r = submit(stu1, 4001, "PYTHON", AC_PY, 5001)  # 5001 已结束
    check("B6 向已结束竞赛提交被拒", r.get("code") != 200, f"msg={str(r.get('msg'))[:70]}")

    # ==================== E-part1. WebSocket 进度推送（非竞赛提交） ====================
    print("\n—— E. WebSocket 推送（先测判题进度）——")
    p1 = ws_expect_reject("/ws/submissions/1")
    check("E1 无 token 订阅被拒（网关握手 401）", p1[0], p1[1])

    d = submit(stu1, 4001, "PYTHON", AC_PY, 0)     # contestId=0，不影响任何榜单
    sid = (d.get("data") or {}).get("id")
    ws = None
    try:
        ws = ws_connect(f"/ws/submissions/{sid}", stu1_tok)
        frames = ws_collect(ws, 70, stop_types=["SUB_RESULT"])
    finally:
        if ws:
            try:
                ws.close()
            except Exception:
                pass
    types = [f.get("type") for f in frames]
    check("E2 本人订阅判题进度：收到 CONNECTED + SNAPSHOT",
          "CONNECTED" in types and "SNAPSHOT" in types, f"types={types[:8]}")
    check("E3 判题中途收到 SUB_PROGRESS 进度帧", "SUB_PROGRESS" in types,
          f"progress={types.count('SUB_PROGRESS')} 帧")
    check("E4 判题终态收到 SUB_RESULT", "SUB_RESULT" in types, f"types={types[-6:]}")

    _v, sub_detail = wait_terminal(stu1, sid, timeout_s=30)
    other_ok, other_detail = True, ""
    try:
        ws_o = ws_connect(f"/ws/submissions/{sid}", stu2_tok)
        try:
            frames_o = ws_collect(ws_o, 8, stop_types=["ERROR", "CONNECTED"])
            other_ok = any(m.get("type") == "ERROR" for m in frames_o)
            other_detail = f"frames={[m.get('type') for m in frames_o]}"
        finally:
            ws_o.close()
    except Exception as e:
        other_detail = f"握手被拒（{type(e).__name__}）"
    check("E5 越权订阅他人提交进度被拒（仅本人/教师）", other_ok, other_detail)

    # ==================== C. Redis ZSet 榜单与同分规则 ====================
    print("\n—— C. Redis ZSet 排行榜与同分罚时规则 ——")

    # C1：学员一先 WA（累计罚时）后 AC（首次通过才计分）
    judge(stu1, 4001, "PYTHON", WA_PY, cid, expect="WA",
          name="C1 学员一首次提交错误 → WA（此时不计分）")
    vo = wait_rank(stu1, cid, lambda v: v.get("entries"))
    e1 = entries_map(vo).get(2001) or {}
    check("C2 首次 AC 前不计分（weight=0，该题记为 -1）",
          e1.get("weight") == 0 and e1.get("problemStatus", {}).get("A") == "-1",
          f"weight={e1.get('weight')} status={e1.get('problemStatus')}")

    judge(stu1, 4001, "PYTHON", AC_PY, cid, expect="AC", name="C3 学员一通过 A 题 → AC")
    judge(stu2, 4001, "PYTHON", AC_PY, cid, expect="AC", name="C4 学员二直接通过 A 题 → AC")

    vo = wait_rank(stu1, cid, lambda v: len(entries_map(v).get(2001, {})) > 0
                   and entries_map(v).get(2001, {}).get("weight") == 1)
    em = entries_map(vo)
    s1, s2 = em.get(2001), em.get(2002)
    check("C5 榜单包含两名参赛者", s1 is not None and s2 is not None, f"participants={vo.get('totalParticipants')}")

    if s1 and s2:
        check("C6 同题同分：罚时少者名次靠前（学员二在学员一之前）",
              s2.get("rank") == 1 and s1.get("rank") == 2 and s2.get("penaltySeconds") < s1.get("penaltySeconds"),
              f"学员一 rank={s1.get('rank')} 罚时={s1.get('penaltySeconds')}s；"
              f"学员二 rank={s2.get('rank')} 罚时={s2.get('penaltySeconds')}s")
        # 罚时构成 = Σ(该题 AC 前的错误数 × 1200) + (AC 时刻 − 开赛时刻)。
        # 两人各通过 A 题：学员一多一次错误提交(+1200)，但 AC 得更早(−Δt)。
        # 因此**直接用两人的 AC 时刻差把 1200 还原出来**才是精确断言；只比 1200 会误判（P4 首轮踩过）。
        d_solved = s1.get("penaltySeconds", 0) - s2.get("penaltySeconds", 0)
        d_offset = (s1.get("lastAcceptedOffsetSeconds") or 0) - (s2.get("lastAcceptedOffsetSeconds") or 0)
        check("C7 罚时构成 = 1 次错误提交×1200s + 两人 AC 时刻差",
              abs(d_solved - 1200 - d_offset) <= 1,
              f"Δpenalty={d_solved}s = 1200 + ΔAC时刻({d_offset}s)")
        check("C8 ICPC 逐题记法：学员一 A='+1'、学员二 A='+'",
              s1.get("problemStatus", {}).get("A") == "+1" and s2.get("problemStatus", {}).get("A") == "+",
              f"学员一={s1.get('problemStatus')} 学员二={s2.get('problemStatus')}")
        check("C9 榜单行同时下发 weight / solvedCount / penaltySeconds（前端可解释排序）",
              s1.get("weight") == 1 and s1.get("solvedCount") == 1 and isinstance(s1.get("penaltySeconds"), int),
              f"weight={s1.get('weight')} solved={s1.get('solvedCount')} penalty={s1.get('penaltySeconds')}")

        # C10：ZSet 原始编码校验（可直接读 Redis 时必须成立）
        raw = redis_cmd("ZREVRANGE", f"judge:contest:rank:{cid}", "0", "-1", "WITHSCORES")
        if raw is None:
            skip("C10 Redis ZSet 原始编码（score = weight×10^7 + 10^7-1-罚时）",
                 "无法访问 codejudge-redis，跳过直读校验")
        else:
            flat = [x.strip() for x in raw.split() if x.strip()]
            pairs = {flat[i]: flat[i + 1] for i in range(0, len(flat) - 1, 2)}
            # score = 权重×10^12 + (999999-罚时)×10^6 + (999999-末次通过偏移)
            expected = (1 * 10 ** 12
                        + (999_999 - s2.get("penaltySeconds", 0)) * 10 ** 6
                        + (999_999 - s2.get("lastAcceptedOffsetSeconds", 0)))
            got = pairs.get("2002")
            check("C10 Redis ZSet 原始编码（权重/罚时/末次通过 三段）",
                  got is not None and abs(int(got) - expected) <= 2,
                  f"key=judge:contest:rank:{cid} member=2002 score={got} 期望≈{expected}")

    # C11：学员三报名后仅错误提交 → weight 仍为 0
    requests.post(f"{GATEWAY}/contests/{cid}/register", headers=stu3, timeout=TIMEOUT)
    judge(stu3, 4001, "PYTHON", WA_PY, cid, expect="WA", name="C11 学员三仅提交错误解 → WA")
    vo = wait_rank(stu1, cid, lambda v: len(entries_map(v).get(2003, {})) > 0)
    s3 = entries_map(vo).get(2003)
    check("C12 从未通过者不参与解题数排名（weight=0）", s3 is not None and s3.get("weight") == 0,
          f"学员三 weight={s3.get('weight') if s3 else None} status={s3.get('problemStatus') if s3 else None}")

    # C13：学员一再通过 B 题 → 通过题数优先于罚时，反超登顶
    judge(stu1, 4002, "PYTHON", "n = int(input()); print(n * (n + 1) // 2)", cid,
          expect="AC", name="C13 学员一通过 B 题 → AC")
    vo = wait_rank(stu1, cid, lambda v: (entries_map(v).get(2001) or {}).get("weight") == 2)
    em = entries_map(vo)
    as1, as2 = em.get(2001), em.get(2002)
    check("C14 通过题数（权重）优先于罚时：2 题者排 1 题者之前",
          as1 and as2 and as1.get("rank") == 1 and as2.get("rank") == 2,
          f"学员一 weight={as1.get('weight') if as1 else None} rank={as1.get('rank') if as1 else None}；"
          f"学员二 weight={as2.get('weight') if as2 else None} rank={as2.get('rank') if as2 else None}")

    # ==================== E-part2. 榜单订阅（WebSocket） ====================
    print("\n—— E（续）. 榜单订阅鉴权与推送 ——")
    p3 = ws_expect_reject(f"/ws/contests/{cid}/rank")
    check("E6 无 token 订阅榜单被拒（网关握手 401）", p3[0], p3[1])

    # 非特权用户请求 full 视图 → 必须 fail-closed
    # 断言强度：token 合法时网关会放行握手，拒绝发生在业务端点内 —— 因此这里要求**收到 ERROR 信封**
    # （而不是「握手失败」），否则调用方只看到连接被关、不知道原因，也就无法区分
    # 「无权」与「网络抖了一下」这两种完全不同的处置。
    try:
        ws_np = ws_connect(f"/ws/contests/{cid}/rank", stu1_tok, params="full=true")
        try:
            fr = ws_collect(ws_np, 8, stop_types=["ERROR", "CONNECTED"])
            np_ok = any(m.get("type") == "ERROR" for m in fr)
            np_detail = f"frames={[m.get('type') for m in fr]}"
        finally:
            ws_np.close()
    except Exception as e:
        np_ok, np_detail = False, f"握手异常（{type(e).__name__}）——预期应连上后被 ERROR 拒绝"
    check("E7 学员请求 full 榜单视图被拒（fail-closed + 回 ERROR 说明原因）", np_ok, np_detail)

    ws_rank = ws_connect(f"/ws/contests/{cid}/rank", stu1_tok)
    try:
        boot = ws_collect(ws_rank, 12, stop_types=["SNAPSHOT"])
        btypes = [m.get("type") for m in boot]
        snap = next((m for m in boot if m.get("type") == "SNAPSHOT"), None)
        snap_data = (snap or {}).get("data") or {}
        check("E8 连接即推 CONNECTED + SNAPSHOT（含当前榜单）",
              "CONNECTED" in btypes and snap is not None and snap_data.get("entries") is not None,
              f"types={btypes}")
        check("E9 公开会话的快照视图标记 frozen/fullView 语义正确",
              snap is not None and snap_data.get("fullView") is False,
              f"frozen={snap_data.get('frozen')} fullView={snap_data.get('fullView')}")

        ver_before = snap_data.get("version")
        ws_rank.send('{"type":"PING"}')
        pong = ws_collect(ws_rank, 6, stop_types=["PONG"])
        check("E10 心跳 PING → PONG", any(m.get("type") == "PONG" for m in pong),
              f"types={[m.get('type') for m in pong]}")

        # 触发榜单变更：学员二通过 B 题 → 应收到 RANK_UPDATE（合并窗口后推送）
        judge(stu2, 4002, "PYTHON", "n = int(input()); print(n * (n + 1) // 2)", cid, expect="AC")
        updates = ws_collect(ws_rank, 60, stop_types=["RANK_UPDATE"])
        ups = [m for m in updates if m.get("type") == "RANK_UPDATE"]
        check("E11 榜单变更经 WebSocket 推送 RANK_UPDATE", len(ups) >= 1,
              f"types={[m.get('type') for m in updates][:10]}")
        if ups:
            ver_after = ups[-1].get("seq") or (ups[-1].get("data") or {}).get("version")
            check("E12 推送版本号单调递增（客户端据此判丢包）",
                  isinstance(ver_before, int) and isinstance(ver_after, int) and ver_after > ver_before,
                  f"version {ver_before} -> {ver_after}")
    finally:
        try:
            ws_rank.close()
        except Exception:
            pass

    # ==================== D. 封榜与视图隔离 ====================
    print("\n—— D. 封榜：公众视图冻结 / 内部全量可见 ——")
    r = requests.post(f"{GATEWAY}/contests/{cid}/freeze", headers=teacher, timeout=TIMEOUT).json()
    check("D1 教师手动封榜成功", r.get("code") == 200 and r.get("data") is True,
          f"code={r.get('code')} data={r.get('data')}")

    pub_before, _ = get_rank(stu1, cid)
    check("D2 封榜后公众榜标记 frozen=true / inFreezeWindow=true",
          pub_before.get("frozen") is True and pub_before.get("inFreezeWindow") is True
          and pub_before.get("fullView") is False,
          f"frozen={pub_before.get('frozen')} inFreeze={pub_before.get('inFreezeWindow')}")

    pub_sig_before = json.dumps(pub_before.get("entries") or [], sort_keys=True, ensure_ascii=False)

    # 封榜期间的成绩：学员三通过 A 题（应只进内部实时榜，不进公开冻结榜）
    judge(stu3, 4001, "PYTHON", AC_PY, cid, expect="AC",
          name="D3 封榜期间学员三通过 A 题 → AC（成绩仍被记录）")
    time.sleep(3)

    pub_after, _ = get_rank(stu1, cid)
    pub_sig_after = json.dumps(pub_after.get("entries") or [], sort_keys=True, ensure_ascii=False)
    check("D4 公开冻结榜在封榜后不再变化（封榜期间成绩不进公开榜）",
          pub_sig_before == pub_sig_after,
          f"冻结前 {len(pub_before.get('entries') or [])} 行 / 冻结后 {len(pub_after.get('entries') or [])} 行")

    # 逐题明细也必须冻结。只冻结名次是不够的：problemStatus 读的是实时状态 Hash，
    # 不一起冻结就会出现「名次没动、但单元格从 -1 变成 +」——等于公开宣布封榜后谁过了题。
    pub_e3 = entries_map(pub_after).get(2003) or {}
    check("D4b 封榜后公开榜的逐题明细未泄漏（学员三 A 题仍为 -1，未变成 +）",
          pub_e3.get("problemStatus", {}).get("A") == "-1",
          f"公开榜学员三明细={pub_e3.get('problemStatus')}")

    full_vo, full_body = get_rank(teacher, cid, full=True)
    fe = entries_map(full_vo or {})
    check("D5 教师 full=true 可见实时榜（fullView=true、frozen=false）",
          full_body.get("code") == 200 and full_vo.get("fullView") is True and full_vo.get("frozen") is False,
          f"fullView={full_vo.get('fullView') if full_vo else None} frozen={full_vo.get('frozen') if full_vo else None}")
    check("D6 全量榜含封榜期间的新成绩（学员三已通过 A 题）",
          (fe.get(2003) or {}).get("weight") == 1,
          f"学员三 full 榜 weight={(fe.get(2003) or {}).get('weight')}")

    r = requests.get(f"{GATEWAY}/contests/{cid}/rank?full=true", headers=stu2, timeout=TIMEOUT).json()
    check("D7 学员请求 full=true 被拒（403，显式拒绝而非静默降级）", r.get("code") != 200,
          f"code={r.get('code')} msg={str(r.get('msg'))[:70]}")

    r = requests.get(f"{GATEWAY}/contests/{cid}/snapshots", headers=teacher, timeout=TIMEOUT).json()
    snaps = (r.get("data") or [])
    check("D8 封榜快照已留档（contest_rank_snapshot.type=FROZEN）",
          any(s.get("type") == "FROZEN" for s in snaps), f"snapshots={snaps}")

    r = requests.post(f"{GATEWAY}/contests/{cid}/freeze", headers=teacher, timeout=TIMEOUT).json()
    check("D9 重复封榜幂等（第二次返回 false）", r.get("code") == 200 and r.get("data") is False,
          f"data={r.get('data')}")

    # ==================== G. 终榜重建 ====================
    print("\n—— G. 终榜重建（从提交表回放）——")
    before_vo, _ = get_rank(teacher, cid, full=True)
    before_sig = json.dumps(before_vo.get("entries") or [], sort_keys=True, ensure_ascii=False)
    r = requests.post(f"{GATEWAY}/contests/{cid}/rank/rebuild", headers=teacher, timeout=TIMEOUT).json()
    rep = r.get("data") or {}
    check("G1 重建完成并返回回放报告", r.get("code") == 200 and rep.get("replayed", 0) >= 1,
          f"fetched={rep.get('fetched')} replayed={rep.get('replayed')} changed={rep.get('changed')} "
          f"participants={rep.get('participants')} took={rep.get('tookMs')}ms")
    after_vo, _ = get_rank(teacher, cid, full=True)
    after_sig = json.dumps(after_vo.get("entries") or [], sort_keys=True, ensure_ascii=False)
    check("G2 重建结果与重建前一致（回放幂等、未破坏既有榜单）", before_sig == after_sig,
          f"重建前 {len(before_vo.get('entries') or [])} 行 / 重建后 {len(after_vo.get('entries') or [])} 行")

    r = requests.post(f"{GATEWAY}/contests/{cid}/rank/rebuild", headers=stu1, timeout=TIMEOUT).json()
    check("G3 学员触发重建被拒（教师/管理员专属）", r.get("code") != 200, f"code={r.get('code')}")

    # ==================== F. 自动封榜 → 结束解封（慢用例） ====================
    if SKIP_SLOW:
        skip("F. 自动封榜与解封（短赛程竞赛）", "CJ_P4_SKIP_SLOW=1")
    else:
        print("\n—— F. 自动封榜 → 结束 → 解封（等待短赛程竞赛自然结束，约 100 秒）——")
        now2 = datetime.now()
        payload = {
            "title": "【P4验收】自动封榜与解封（短赛程）",
            "description": "verify-p4.py 自动创建；封榜时刻已过，用于验证生命周期扫描的自动封榜与结束解封。",
            "rule": "ACM",
            "startTime": iso(now2 - timedelta(minutes=30)),
            "endTime": iso(now2 + timedelta(seconds=100)),
            "freezeMinutes": 3,          # 封榜时刻 = 结束 - 3 分钟，已落在过去 → 创建即处于封榜窗口
            "penaltyMinutes": 20,
            "problems": [{"problemId": 4001, "label": "A", "displayOrder": 0, "fullScore": 100}],
        }
        r = requests.post(f"{GATEWAY}/contests", headers=teacher, json=payload, timeout=TIMEOUT).json()
        cid2 = (r.get("data") or {}).get("id")
        check("F1 创建已处于封榜窗口的短赛程竞赛", r.get("code") == 200 and cid2 is not None,
              f"contestId={cid2} freezeAt={(r.get('data') or {}).get('freezeAt')}")

        # 生命周期扫描（10s 一轮）应自动封榜
        frozen_ok = False
        deadline = time.time() + 40
        while time.time() < deadline:
            s = requests.get(f"{GATEWAY}/contests/{cid2}/snapshots", headers=teacher, timeout=TIMEOUT).json()
            if any(x.get("type") == "FROZEN" for x in (s.get("data") or [])):
                frozen_ok = True
                break
            time.sleep(2)
        check("F2 生命周期扫描自动封榜（无需人工干预）", frozen_ok, "FROZEN 快照已生成" if frozen_ok else "40s 内未封榜")

        requests.post(f"{GATEWAY}/contests/{cid2}/register", headers=stu1, timeout=TIMEOUT)
        judge(stu1, 4001, "PYTHON", AC_PY, cid2, expect="AC", name="F3 封榜期间提交并判题 → AC")
        time.sleep(3)
        pub2, _ = get_rank(stu1, cid2)
        full2, _ = get_rank(teacher, cid2, full=True)
        check("F4 封榜期间成绩不进公开榜", not (pub2.get("entries") or []) and pub2.get("frozen") is True,
              f"公开榜 {len(pub2.get('entries') or [])} 行 frozen={pub2.get('frozen')}")
        check("F5 封榜期间成绩在内部全量榜可见",
              (entries_map(full2).get(2001) or {}).get("weight") == 1,
              f"内部榜 {len(full2.get('entries') or [])} 行")

        # 等待竞赛自然结束 → 自动终榜 + 自动解封
        ended = False
        deadline = time.time() + 150
        while time.time() < deadline:
            d2 = requests.get(f"{GATEWAY}/contests/{cid2}", headers=teacher, timeout=TIMEOUT).json()
            if (d2.get("data") or {}).get("status") == 2:
                ended = True
                break
            time.sleep(3)
        check("F6 竞赛到点自动推进为已结束", ended, "status=2" if ended else "150s 内未结束")

        time.sleep(12)   # 留一轮扫描时间写终榜快照
        pub3, _ = get_rank(stu1, cid2)
        check("F7 结束后公开榜自动解封（frozen=false）", pub3.get("frozen") is False,
              f"frozen={pub3.get('frozen')} inFreezeWindow={pub3.get('inFreezeWindow')}")
        check("F8 解封后公开榜 = 完整榜（封榜期间的成绩从未丢失）",
              (entries_map(pub3).get(2001) or {}).get("weight") == 1,
              f"公开榜 {len(pub3.get('entries') or [])} 行")
        s = requests.get(f"{GATEWAY}/contests/{cid2}/snapshots", headers=teacher, timeout=TIMEOUT).json()
        types = [x.get("type") for x in (s.get("data") or [])]
        check("F9 终榜留档（FROZEN + FINAL 两条快照）",
              "FROZEN" in types and "FINAL" in types, f"snapshots={types}")

    print("\n" + "=" * 74)
    print(f"结果：PASS={PASS}  FAIL={FAIL}  SKIP={SKIP}")
    print(f"本次创建的竞赛：主赛={cid}" + ("" if SKIP_SLOW else f"  短赛程={cid2}"))
    print("=" * 74)
    sys.exit(0 if FAIL == 0 else 1)


if __name__ == "__main__":
    main()
