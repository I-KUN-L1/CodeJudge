#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
实测 reset-demo-data.py 里每个代码片段的**真实**判题结论。

────────────────────────────────────────────────────────────────────────────────
为什么需要它
────────────────────────────────────────────────────────────────────────────────
`CODE` 字典的键形如 `4001:WA:JAVA`，中间的 `WA` 是"我以为它会被判 WA"。这个"以为"
在 Java/Python 上多半成立（能对着 verify-p3.py 已验过的片段抄），但 CPP / GO 的片段
是我另写的 —— 于是出现过：

  · 全部 CPP / GO 提交被判 CE（真实原因：nonce 注释用了 `#`，C++ 当预处理指令、
    Go 当非法字符）；
  · `4003:AC:PYTHON` 实测 WA、`4003:AC:JAVA` 实测 TLE —— 我标成 AC 的片段根本没解题。

这些只有**真提交一次**才知道。本脚本把每个片段跑一遍，打印实测结论，并按
"实测 vs 键里标注"的一致性给出对账表 —— 键名标错的地方一目了然。

用法：
    python scripts/probe-snippet-verdicts.py            # 全部片段各跑一次
    python scripts/probe-snippet-verdicts.py 4001 4003  # 只跑指定题目

前置：8 个服务已启动（`python scripts/start-all.py --no-infra --wait`）。
注意：会真实消耗判题资源（约 33 次提交，1–3 分钟），并写入提交表。
      跑完请用 `--phases clean,seed` 重置成正式演示数据。
"""

import collections
import json
import pathlib
import sys
import time

# 复用 reset-demo-data.py 的辅助函数与清单（不重复实现登录/提交/轮询）
_ROOT = pathlib.Path(__file__).resolve().parent.parent
_SRC = (_ROOT / "scripts" / "reset-demo-data.py").read_text(encoding="utf-8")
_SRC = _SRC.rsplit("\ndef main(", 1)[0]          # 去掉 main()，避免它被执行

ns = {"__name__": "rdd", "__file__": str(_ROOT / "scripts" / "reset-demo-data.py")}
exec(compile(_SRC, "reset-demo-data.py", "exec"), ns)

CODE = ns["CODE"]
STUDENTS = ns["STUDENTS"]
STUDENT_PASS = ns["STUDENT_PASS"]
NONCE_PREFIX = ns["NONCE_PREFIX"]
login = ns["login"]
submit = ns["submit"]
wait_terminal = ns["wait_terminal"]
dt = ns["dt"] if "dt" in ns else __import__("datetime")


def main():
    wanted = set(sys.argv[1:])
    keys = [k for k in CODE if not wanted or k.split(":")[0] in wanted]
    # 稳定顺序：按题目、然后结论、然后语言，输出好读
    keys.sort(key=lambda k: (k.split(":")[0], k.split(":")[1], k.split(":")[2]))
    print(f"待实测片段 {len(keys)} 个\n")

    # 五个学员账号轮转登录，既避开单账号 30 次/分钟的提交限流，
    # 也让"实测"这件事本身不依赖某一个账号。
    print("登录 5 个演示学员账号……")
    tokens = {}
    for pid, phone, name in STUDENTS:
        tokens[pid] = login(phone, STUDENT_PASS)
        print(f"  ✓ {name}（{phone}）")
    ids = [pid for pid, _p, _n in STUDENTS]

    run_nonce = int(time.time()) % 100000
    results = []
    t0 = time.time()
    for i, key in enumerate(keys):
        problem_s, expect, lang = key.split(":")
        problem = int(problem_s)
        uid = ids[i % len(ids)]
        code = CODE[key] + f"\n{NONCE_PREFIX[lang]} probe{run_nonce}#{i}"
        r = submit(tokens[uid], problem, lang, code, 0)
        data = r.get("data") or {}
        if r.get("code") != 200 or not data.get("id"):
            print(f"  ✗ {key:<18} 提交被拒：{json.dumps(r, ensure_ascii=False)[:140]}")
            results.append((key, lang, expect, "SUBMIT_FAIL", None))
            continue
        detail = wait_terminal(tokens[uid], data["id"])
        got = detail.get("verdict") or detail.get("status")
        ms = detail.get("timeMs")
        ok = got == expect
        results.append((key, lang, expect, got, ms))
        print(f"  {'✓' if ok else '!'} {key:<18} u{uid} → 实测 {got:<4}"
              f"（键里标 {expect}）{ms if ms else '-'}ms")

    # ---------------- 对账 ----------------
    agree = [r for r in results if r[2] == r[3]]
    disagree = [r for r in results if r[2] != r[3] and r[3] != "SUBMIT_FAIL"]
    failed = [r for r in results if r[3] == "SUBMIT_FAIL"]

    print(f"\n{'=' * 76}\n实测完成，耗时 {time.time() - t0:.0f}s")
    print(f"  标注与实测一致：{len(agree)} / {len(results)}")
    if disagree:
        print(f"\n  标注与实测**不一致**（这些都是键名标错，或片段没达到预期语义）：")
        for key, lang, expect, got, _ms in disagree:
            print(f"    {key:<18} 标 {expect:<4} → 实测 {got}")
    if failed:
        print(f"\n  提交失败：")
        for key, lang, expect, got, _ms in failed:
            print(f"    {key}")

    print(f"\n  实测结论分布：")
    dist = collections.Counter(r[3] for r in results)
    for v, c in sorted(dist.items()):
        print(f"    {v:<6} {c}")

    out = _ROOT / "logs" / "snippet-verdicts.json"
    out.write_text(json.dumps(
        [{"key": k, "lang": l, "declared": d, "observed": o, "timeMs": m}
         for k, l, d, o, m in results], ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n明细已写入 {out}")
    print("\n⚠️ 提交表已被本次实测污染，正式演示数据请重新执行："
          "\n   python scripts/reset-demo-data.py --yes --phases clean,seed,rank,verify")
    return 0 if not disagree and not failed else 1


if __name__ == "__main__":
    sys.exit(main())
