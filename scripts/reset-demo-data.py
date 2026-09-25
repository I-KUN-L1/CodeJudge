#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CodeJudge 演示数据重置：备份 → 清洗 → 灌入 → 重建榜单 → 校验。

────────────────────────────────────────────────────────────────────────────────
为什么需要这个脚本
────────────────────────────────────────────────────────────────────────────────
联调与验收跑过几轮之后，库里会积下大量**只对那一次运行有意义**的数据：

  · 压测账号池（perf-test/seed-users.py 造的 `load000000`…`load000119`）；
  · 验收脚本临时建的 `验收临时学员/教师`、`p3admin`；
  · 1600+ 条提交与 5000+ 条用例结果、2000+ 条登录记录；
  · 6 场验收竞赛（全是 `verify-p4.py` 造的，状态已过期）。

它们不是"脏数据"（结构合法），但会让新接手的人分不清"哪些是基线、哪些是某次测试的渣"。
而 `sql/seed.sql` 里的演示竞赛时间是**相对 NOW() 写死**的，过期后竞态就错了
（那场"进行中"的演示赛本应还在赛程内，却和已结束的那场一样变成"已结束"）。

本脚本把这件事做成一条可重放的命令，并坚持两条原则：

  1. **先备份，再动手。**（`--yes` 时才落盘，备份目标与体积会打印出来）
  2. **演示数据不造假。** 提交记录**走真实接口、由判题机真判**，不直接 INSERT
     verdict —— 否则"演示数据"与真实行为不一致，一看就露馅（点开详情
     逐用例结果对不上，或重交同一份代码得到不同结论）。
     唯一例外是 pgvector 的向量（见 `pseudo_embedding` 的说明）。

────────────────────────────────────────────────────────────────────────────────
五个阶段
────────────────────────────────────────────────────────────────────────────────
    backup  mysqldump 5 库 + pg_dump 1 库 → backups/<时间戳>/
    clean   删除压测/验收残留、清空提交域与竞赛域、清 Redis 的 judge:* 、清 PG 两表
    seed    建 3 场竞赛（已结束/进行中/未开始）→ 报名 → **真实提交 55 次**（清单条数由
            NON_CONTEST/CONTEST_ENDED/CONTEST_RUNNING 决定，改清单即改这个数）→
            回填通过率计数 → 灌 PG 知识切片与历史点评。
            注意：这里**不**用 SQL 改竞赛状态（原因见 phase_seed 3.1 的注释）
    rank    调 `POST /contests/{id}/rank/rebuild` 从提交表回放重算 ZSet 榜单；
            对"已结束"那场**在赛程内** refreeze 生成封榜快照，再把 end_time 推回过去，
            由 ContestLifecycleService 的 10s 扫描推进状态并写终榜快照（FINAL）
    verify  交叉校验（每条提交都有任务、计数与聚合一致、榜单人数对得上）

**依赖**：clean 需要 Docker；seed/rank 需要 8 个服务已启动（`python scripts/start-all.py --no-infra --wait`）。

────────────────────────────────────────────────────────────────────────────────
用法
────────────────────────────────────────────────────────────────────────────────
    python scripts/reset-demo-data.py                     # 预演：只打印将做什么与影响面
    python scripts/reset-demo-data.py --yes               # 全套执行
    python scripts/reset-demo-data.py --yes --phases clean,seed
    python scripts/reset-demo-data.py --yes --skip-backup # 已有本次备份时（不建议）
    python scripts/reset-demo-data.py --yes --phases verify

⚠️ 破坏性：`clean` 会 **DELETE/TRUNCATE** 上面列出的数据。预演模式默认开启，
   请先看一遍输出里的「影响面」表再决定是否 `--yes`。
"""

import argparse
import datetime as dt
import hashlib
import json
import math
import os
import pathlib
import subprocess
import sys
import time

import requests

ROOT = pathlib.Path(__file__).resolve().parent.parent
GATEWAY = os.environ.get("CJ_RESET_GATEWAY", "http://localhost:9080")
TIMEOUT = 20

MYSQL_CTR = "codejudge-mysql"
PG_CTR = "codejudge-pg"
REDIS_CTR = "codejudge-redis"

MYSQL_DBS = ["judge_user", "judge_auth", "judge_problem", "judge_submission", "judge_contest"]

# 三场演示竞赛的**标题** —— 这是它们在库里的唯一稳定标识。
#
# 早先版本这里写的是"预留 id 段位"（5001/5002/5003），那是自欺：竞赛 id 由服务端
# 雪花算法生成（~2.1e18），客户端根本指定不了 —— `DELETE WHERE id = <预留值>` 删不掉
# 任何东西，POST 回来的 id 也不是预留值，只会在日志里刷一串无意义的 WARN。
# 改成按标题查之后，seed / rank / verify 三个阶段即使各自独立运行也能对上同一批竞赛。
DEMO_TITLES = {
    "ended": "【演示】算法热身赛（已结束 · 含终榜与封榜快照）",
    "running": "【演示】周赛 #1（进行中 · 实时榜）",
    "upcoming": "【演示】新生赛（未开始）",
}
DEMO_PROBLEMS = [4001, 4002, 4003, 4004, 4005]
DRAFT_PROBLEM = 4006

# 学生账号（sql/seed.sql 的基线，8 个验收脚本依赖 13900000001 / 13900000011 与口令 123456）
STUDENTS = [
    (2001, "13900000001", "演示学员一"),
    (2002, "13900000002", "演示学员二"),
    (2003, "13900000003", "演示学员三"),
    (2004, "13900000004", "演示学员四"),
    (2005, "13900000005", "演示学员五"),
]
STUDENT_PASS = "123456"
TEACHER_PHONE = "13900000011"  # 演示教师一

# 各语言的行注释符号 —— 用于给代码追加 nonce（见 do_submit）。
# 漏了哪种语言，那种语言的提交就会**因注释符号非法而全部 CE**。
NONCE_PREFIX = {"PYTHON": "#", "JAVA": "//", "CPP": "//", "GO": "//"}

# 判定代码目录：每种片段对应一个**确定的**判题结论，理由见下方注释。
# 命名与 verify-p3.py 的 A 段保持一致口径；TLE/RE 片段直接沿用该脚本里已被验证过的写法。
CODE = {
    # ---- 4001 A+B：隐藏用例 3 是 2147483647+2147483647，32 位相加必然溢出 → WA ----
    "4001:AC:PYTHON": "a, b = map(int, input().split())\nprint(a + b)",
    "4001:AC:JAVA": """import java.util.Scanner;
public class Main {
  public static void main(String[] args) {
    Scanner sc = new Scanner(System.in);
    long a = sc.nextLong(), b = sc.nextLong();
    System.out.println(a + b);
  }
}""",
    "4001:AC:GO": """package main

import "fmt"

func main() {
	var a, b int64
	fmt.Scan(&a, &b)
	fmt.Println(a + b)
}""",
    "4001:WA:JAVA": """import java.util.Scanner;
public class Main {
  public static void main(String[] args) {
    Scanner sc = new Scanner(System.in);
    int a = sc.nextInt(), b = sc.nextInt();
    System.out.println(a + b);
  }
}""",
    "4001:WA:PYTHON": "a, b = map(int, input().split())\nprint(a - b)",
    # C++ 的位宽对照组：long long 通过 / int 溢出（与上面 Java 两条同义，只是换语言）
    "4001:AC:CPP": """#include <iostream>
int main() {
  long long a, b;
  std::cin >> a >> b;
  std::cout << a + b << std::endl;
  return 0;
}""",
    "4001:WA:CPP": """#include <iostream>
int main() {
  int a, b;
  std::cin >> a >> b;
  std::cout << a + b << std::endl;
  return 0;
}""",
    "4001:RE:PYTHON": "a, b = map(int, input().split())\nprint(a // (a - a))",
    "4001:RE:CPP": """#include <iostream>
int main() {
  long long a, b;
  std::cin >> a >> b;
  int *p = nullptr;
  *p = 1;
  std::cout << (a + b) << std::endl;
  return 0;
}""",
    "4001:CE:JAVA": "public class Main { public static void main(String[] args { }",
    # ---- 4002 1..n 求和：n 可达 1e9，朴素循环必 TLE；32 位公式会溢出 ----
    "4002:AC:PYTHON": "n = int(input())\nprint(n * (n + 1) // 2)",
    "4002:AC:JAVA": """import java.util.Scanner;
public class Main {
  public static void main(String[] args) {
    long n = new Scanner(System.in).nextLong();
    System.out.println(n * (n + 1) / 2);
  }
}""",
    "4002:AC:GO": """package main

import "fmt"

func main() {
	var n int64
	fmt.Scan(&n)
	fmt.Println(n * (n + 1) / 2)
}""",
    "4002:TLE:PYTHON": "n = int(input())\ns = 0\nfor i in range(1, n + 1):\n    s += i\nprint(s)",
    # 朴素 O(n) 解法在 4002 上触发 TLE（n=1e9，时限 1000ms）。
    #
    # ⚠️ 这条片段改了两轮，全都是实测逼出来的：
    #    ① 原始写法 `for(...) s += i;`（s 是普通变量）→ GCC -O2 把求和**折叠成闭式公式**，
    #       实测 220ms 直接 AC，TLE 根本不复现；
    #    ② 只给 s 加 volatile → 实测 242ms，仍 AC。说明循环体太轻，现代 CPU 的单次
    #       volatile 读改写能在 ~0.25ns 内完成；
    #    ③ 本版：`s` 仍**正确累加**（否则小用例先判 WA，走不到大 n），另用一个不参与输出的
    #       `volatile unsigned noise` 加一层内层循环制造真实内存往返。前两版实测分别
    #       220ms / 242ms / 724ms（都还在 1000ms 门槛内，判 AC），本版约 4e9 次读改写 → 稳定 TLE。
    #    结论（值得记住的一条）：**"朴素循环必然 TLE"是编译器的变量，不是语言的性质。**
    #    要造的 TLE 用例必须**实测**，不能想当然；每次调完都要重跑一遍。
    "4002:TLE:CPP": """#include <iostream>
int main() {
  long long n;
  std::cin >> n;
  volatile long long s = 0;
  volatile unsigned long long noise = 0;
  for (long long i = 1; i <= n; ++i) {
    s = s + i;
    for (unsigned k = 1; k <= 4; ++k) noise = noise * 3u + (unsigned long long)(i + k);
  }
  std::cout << s << std::endl;
  return 0;
}""",
    "4002:WA:JAVA": """import java.util.Scanner;
public class Main {
  public static void main(String[] args) {
    int n = new Scanner(System.in).nextInt();
    System.out.println(n * (n + 1) / 2);
  }
}""",
    # C++ 的等差数列写法：long long 通过 / int 中间结果溢出 → WA
    "4002:AC:CPP": """#include <iostream>
int main() {
  long long n;
  std::cin >> n;
  std::cout << n * (n + 1) / 2 << std::endl;
  return 0;
}""",
    "4002:WA:PYTHON": "n = int(input())\nprint(n * (n - 1) // 2)",
    # ---- 4003 内存受限（32MB）：一次性开巨大数组即 MLE ----
    "4003:MLE:PYTHON": "import sys\nn = int(sys.stdin.readline())\nxs = [0] * (n + 10000000)\nprint(sum(xs[:n]))",
    # ⚠️ Java 的"内存爆炸"在本沙箱**判不出 MLE，只会判 RE**（2026-09-23 实测）。
    #    原因：容器被 cgroup 限到 32MB，JVM 的默认 MaxHeapSize ≈ 容器内存的 1/4，
    #    分配超大数组时 JVM 自己先抛 `OutOfMemoryError` → 进程以**普通非零码**退出，
    #    看板上就落成 RE。而 MLE 的判定依赖进程被 OOM Killer 杀掉（退出码 137）
    #    或内存指标越限 —— 这两种情况在 Java 上都不会出现。
    #    Python / C++ 的内部记录式分配才能真正触发 MLE（见上面两条，实测均为 MLE）。
    #    故这里把键名从 `4003:MLE:JAVA` 改成 `4003:RE:JAVA`：**键名是实测结论，不是设想**。
    "4003:RE:JAVA": """import java.util.Scanner;
public class Main {
  public static void main(String[] args) {
    Scanner sc = new Scanner(System.in);
    int n = sc.nextInt();
    long[] arr = new long[8000000];
    long s = 0;
    for (int i = 0; i < n && sc.hasNextLong(); i++) s += sc.nextLong();
    System.out.println(s + arr[0]);
  }
}""",
    "4003:MLE:CPP": """#include <iostream>
#include <vector>
int main() {
  long long n;
  std::cin >> n;
  std::vector<long long> v(8000000, 0);
  long long s = 0, x;
  while (std::cin >> x) s += x;
  std::cout << (s + v[0]) << std::endl;
  return 0;
}""",
    # 边读边累加的正常解法（不落地整表）。**必须跳过首个数 n** ——
    # 早先写成 `sum(int(x) for x in sys.stdin.read().split())`，把 n 本身也加进了和，
    # 于是拿到 10000010 而不是 10，被判 WA（这条错误实测才发现）。
    "4003:AC:PYTHON": "import sys\nd = sys.stdin.read().split()\nprint(sum(map(int, d[1:])))",
    # Java 同样要**读到 EOF**（跳过首个 n），而不是"读满 n 个"：
    # 4003 的隐藏用例声明 n=1e7 却只给 10 个值，按 n 读会一直等输入 → TLE（实测 1113ms 超时）。
    "4003:AC:JAVA": """import java.io.*;
public class Main {
  public static void main(String[] args) throws Exception {
    StreamTokenizer in = new StreamTokenizer(new BufferedInputStream(System.in));
    long s = 0;
    boolean first = true;
    while (in.nextToken() != StreamTokenizer.TT_EOF) {
      if (first) { first = false; continue; }   // 跳过 n
      s += (long) in.nval;
    }
    System.out.println(s);
  }
}""",
    # ---- 4004 边界：样例 3 个值，max-min=4；打印 max 即 WA；下标越界即 RE ----
    "4004:AC:PYTHON": "n = int(input())\nxs = list(map(int, input().split()))\nprint(max(xs) - min(xs))",
    "4004:AC:CPP": """#include <iostream>
#include <climits>
int main() {
  int n;
  std::cin >> n;
  long long mn = LLONG_MAX, mx = LLONG_MIN, v;
  for (int i = 0; i < n; i++) { std::cin >> v; if (v < mn) mn = v; if (v > mx) mx = v; }
  std::cout << (mx - mn) << std::endl;
  return 0;
}""",
    "4004:AC:GO": """package main

import "fmt"

func main() {
	var n int
	fmt.Scan(&n)
	mn, mx := int64(1<<62), int64(-(1 << 62))
	var v int64
	for i := 0; i < n; i++ {
		fmt.Scan(&v)
		if v < mn {
			mn = v
		}
		if v > mx {
			mx = v
		}
	}
	fmt.Println(mx - mn)
}""",
    "4004:RE:PYTHON": "n = int(input())\nxs = list(map(int, input().split()))\nprint(xs[n] - xs[0])",
    "4004:WA:JAVA": """import java.util.Scanner;
public class Main {
  public static void main(String[] args) {
    Scanner sc = new Scanner(System.in);
    int n = sc.nextInt();
    long mx = Long.MIN_VALUE;
    for (int i = 0; i < n; i++) mx = Math.max(mx, sc.nextLong());
    System.out.println(mx);
  }
}""",
    "4004:WA:PYTHON": "n = int(input())\nxs = list(map(int, input().split()))\nprint(max(xs))",
    # ---- 4005 最短路：堆优化 Dijkstra ----
    "4005:AC:PYTHON": """import sys, heapq
data = sys.stdin.read().split()
if data:
    n, m = int(data[0]), int(data[1])
    g = [[] for _ in range(n + 1)]
    idx = 2
    for _ in range(m):
        u, v, w = int(data[idx]), int(data[idx + 1]), int(data[idx + 2])
        idx += 3
        g[u].append((v, w))
        g[v].append((u, w))
    INF = float('inf')
    dist = [INF] * (n + 1)
    dist[1] = 0
    pq = [(0, 1)]
    while pq:
        d, u = heapq.heappop(pq)
        if d > dist[u]:
            continue
        for v, w in g[u]:
            nd = d + w
            if nd < dist[v]:
                dist[v] = nd
                heapq.heappush(pq, (nd, v))
    print(-1 if dist[n] == INF else dist[n])""",
    "4005:AC:CPP": """#include <bits/stdc++.h>
using namespace std;
int main() {
  int n, m;
  if (!(cin >> n >> m)) return 0;
  vector<vector<pair<int, long long>>> g(n + 1);
  for (int i = 0; i < m; i++) {
    int u, v; long long w;
    cin >> u >> v >> w;
    g[u].push_back({v, w});
    g[v].push_back({u, w});
  }
  const long long INF = LLONG_MAX / 4;
  vector<long long> d(n + 1, INF);
  d[1] = 0;
  priority_queue<pair<long long, int>, vector<pair<long long, int>>, greater<>> pq;
  pq.push({0, 1});
  while (!pq.empty()) {
    auto [du, u] = pq.top(); pq.pop();
    if (du > d[u]) continue;
    for (auto [v, w] : g[u]) if (du + w < d[v]) { d[v] = du + w; pq.push({d[v], v}); }
  }
  cout << (d[n] >= INF ? -1 : d[n]) << endl;
  return 0;
}""",
    "4005:WA:JAVA": """import java.util.Scanner;
public class Main {
  public static void main(String[] args) {
    Scanner sc = new Scanner(System.in);
    int n = sc.nextInt();
    System.out.println(-1);
  }
}""",
    # ⚠️ Python 的语法错误在本沙箱被判 **RE 而非 CE**（2026-09-23 实测）。
    #    原因：沙箱对 Python 没有独立的编译步（不像 Java 走 javac、C++/Go 走 gcc/go build），
    #    解释器加载源码时才报 SyntaxError → 进程以非零码退出，被归到"运行时错误"。
    #    也就是说**界面上按"编译错误"筛选永远筛不到 Python 提交**。
    #    （若认为这是缺陷，修法是在沙箱里为脚本语言加一次 `python -m py_compile` 预检；
    #      这里先按实测把键名改成 RE，不做代码改动。）
    "4005:RE:PYTHON": "def main(:\n    print(1)",
}

# 演示提交清单：字段 = (用户id, 题目id, 竞赛id, 语言, 代码键, 提交时刻偏移(分钟, 相对 NOW()) )
# 说明：
#   · 偏移为负 = 过去。日常提交分布在近 2.5 天内，让"提交时间"列的相对时间有层次；
#     竞赛提交必须落在各自竞赛窗口内（"已结束"那场窗口是 [-120, +10] 分钟）。
#   · 同一 (用户,题目,竞赛) 上刻意安排"先 WA 后 AC"（见 2002 对 4002），
#     否则罚时列恒为 0，罚时规则等于没被演示到。
NON_CONTEST = [
    (2001, 4001, 0, "PYTHON", "4001:AC:PYTHON", -2),
    (2002, 4001, 0, "JAVA", "4001:AC:JAVA", -6),
    (2003, 4001, 0, "CPP", "4001:WA:CPP", -11),
    (2004, 4001, 0, "JAVA", "4001:WA:JAVA", -17),
    (2005, 4001, 0, "PYTHON", "4001:WA:PYTHON", -23),
    (2001, 4001, 0, "PYTHON", "4001:RE:PYTHON", -29),
    (2002, 4001, 0, "CPP", "4001:RE:CPP", -36),
    (2003, 4001, 0, "GO", "4001:AC:GO", -44),
    (2002, 4002, 0, "PYTHON", "4002:AC:PYTHON", -52),
    (2003, 4002, 0, "JAVA", "4002:AC:JAVA", -61),
    (2004, 4002, 0, "PYTHON", "4002:TLE:PYTHON", -70),
    (2005, 4002, 0, "CPP", "4002:TLE:CPP", -80),
    (2001, 4002, 0, "CPP", "4002:TLE:CPP", -92),
    (2002, 4002, 0, "GO", "4002:AC:GO", -105),
    (2003, 4003, 0, "PYTHON", "4003:MLE:PYTHON", -120),
    (2004, 4003, 0, "JAVA", "4003:RE:JAVA", -135),
    (2005, 4003, 0, "CPP", "4003:MLE:CPP", -152),
    (2001, 4003, 0, "PYTHON", "4003:AC:PYTHON", -170),
    (2003, 4004, 0, "PYTHON", "4004:AC:PYTHON", -190),
    (2004, 4004, 0, "PYTHON", "4004:RE:PYTHON", -212),
    (2005, 4004, 0, "JAVA", "4004:WA:JAVA", -236),
    (2001, 4004, 0, "CPP", "4004:AC:CPP", -260),
    (2002, 4005, 0, "PYTHON", "4005:RE:PYTHON", -288),
    (2003, 4005, 0, "PYTHON", "4005:AC:PYTHON", -320),
    (2004, 4005, 0, "JAVA", "4005:WA:JAVA", -360),
    (2001, 4005, 0, "CPP", "4005:AC:CPP", -400),
    (2002, 4001, 0, "JAVA", "4001:CE:JAVA", -1500),
    (2003, 4002, 0, "JAVA", "4002:WA:JAVA", -1700),
    (2004, 4004, 0, "GO", "4004:AC:GO", -2000),
    (2005, 4005, 0, "PYTHON", "4005:AC:PYTHON", -2400),
    (2001, 4003, 0, "JAVA", "4003:AC:JAVA", -2800),
    (2002, 4004, 0, "PYTHON", "4004:WA:PYTHON", -3000),
    (2003, 4003, 0, "PYTHON", "4003:AC:PYTHON", -3200),
    (2004, 4002, 0, "JAVA", "4002:AC:JAVA", -3400),
]

# 已结束那场（含终榜与封榜快照）：A=4001 B=4002 C=4004
CONTEST_ENDED = [
    (2001, 4001, "JAVA", "4001:AC:JAVA", -330),
    (2001, 4002, "JAVA", "4002:AC:JAVA", -300),
    (2002, 4001, "PYTHON", "4001:AC:PYTHON", -335),
    (2002, 4002, "PYTHON", "4002:WA:PYTHON", -310),  # 先错：产生罚时
    (2002, 4002, "PYTHON", "4002:AC:PYTHON", -290),  # 后对
    (2003, 4001, "CPP", "4001:AC:CPP", -325),
    (2003, 4004, "PYTHON", "4004:RE:PYTHON", -260),
    (2003, 4004, "PYTHON", "4004:AC:PYTHON", -240),
    (2004, 4001, "JAVA", "4001:AC:JAVA", -330),
    (2005, 4001, "GO", "4001:AC:GO", -320),
    (2005, 4002, "CPP", "4002:AC:CPP", -250),
    (2004, 4004, "JAVA", "4004:WA:JAVA", -220),
]

# 进行中那场（实时榜）：A=4001 B=4002 C=4003
CONTEST_RUNNING = [
    (2001, 4001, "PYTHON", "4001:AC:PYTHON", -42),
    (2002, 4001, "JAVA", "4001:AC:JAVA", -40),
    (2002, 4002, "JAVA", "4002:AC:JAVA", -30),
    (2003, 4001, "CPP", "4001:AC:CPP", -38) if "4001:AC:CPP" in CODE else (2003, 4001, "GO", "4001:AC:GO", -38),
    (2003, 4003, "PYTHON", "4003:MLE:PYTHON", -20),
    (2004, 4001, "PYTHON", "4001:AC:PYTHON", -35),
    (2005, 4002, "CPP", "4002:AC:CPP", -18),
    (2005, 4002, "PYTHON", "4002:AC:PYTHON", -12),
    (2001, 4002, "PYTHON", "4002:AC:PYTHON", -8),
]

ALLOWED_VERDICTS = ["AC", "WA", "TLE", "MLE", "RE", "CE"]

PASS, FAIL = 0, 0


def _self_check_manifest():
    """清单自检：每条记录的**语言**必须与**代码片段键里的语言**一致。

    加这道闸的原因：早先有 5 条记录写成 `语言=CPP` + `键=4001:AC:GO`（Go 源码）——
    提交进沙箱必然 CE，而日志只显示"结论与预期不符"，归因会一路跑到判题机上，
    白查半天。这类不一致在加载期就该炸，不该等到跑完 55 次提交才发现。
    """
    problems = []
    for name, rows, i_lang, i_key in (
        ("NON_CONTEST", NON_CONTEST, 3, 4),
        ("CONTEST_ENDED", CONTEST_ENDED, 2, 3),
        ("CONTEST_RUNNING", CONTEST_RUNNING, 2, 3),
    ):
        for r in rows:
            lang, key = r[i_lang], r[i_key]
            if key not in CODE:
                problems.append(f"{name}: 代码键不存在 —— {key}")
            elif key.split(":")[2] != lang:
                problems.append(f"{name}: 语言={lang} 与代码键 {key} 不一致")
    missing = [v for v in ALLOWED_VERDICTS
               if not any(k.split(":")[1] == v for k in CODE)]
    if missing:
        problems.append(f"CODE 里缺少这些结论的片段：{missing}（界面上的结论筛选会缺项）")
    if problems:
        raise SystemExit("演示清单自检未通过：\n  " + "\n  ".join(problems))


_self_check_manifest()


# ==========================================================================
# 基础设施命令封装
# ==========================================================================
def env_file_value(key: str) -> str:
    """从仓库根 .env 读变量（与 verify-p3.py 同一套做法：不回显、不硬编码）。"""
    try:
        for line in (ROOT / ".env").read_text(encoding="utf-8", errors="replace").splitlines():
            s = line.strip()
            if not s or s.startswith("#") or "=" not in s:
                continue
            k, v = s.split("=", 1)
            if k.strip() == key:
                return v.strip()
    except OSError:
        pass
    return ""


def sh(cmd, stdin_text=None, check=True) -> str:
    r = subprocess.run(cmd, input=stdin_text, capture_output=True, text=True, encoding="utf-8")
    if check and r.returncode != 0:
        raise SystemExit(f"命令失败（{r.returncode}）：{' '.join(cmd[:3])}…\n{r.stderr[:800]}")
    return r.stdout


def mysql_sql(sql: str, db: str = None) -> str:
    """执行 SQL。

    ⚠️ 两个不能省的细节：
      1. `--default-character-set=utf8mb4` 必须显式传 —— 否则本机 LANG 未设时客户端
         字符集退化为 latin1，中文会被**双重编码**（见 sql/init.sql 顶部同段说明）；
      2. 口令走 `MYSQL_PWD` 环境变量而不是 `-p<pwd>` 参数 —— 后者会出现在进程命令行上，
         任何能 `ps` 的进程都读得到。
    """
    pwd = env_file_value("MYSQL_ROOT_PASSWORD")
    cmd = ["docker", "exec", "-e", f"MYSQL_PWD={pwd}", "-i", MYSQL_CTR,
           "mysql", "-uroot", "--default-character-set=utf8mb4", "-N", "-B"]
    if db:
        cmd += [db]
    body = "SET NAMES utf8mb4;\n" + sql
    return sh(cmd, stdin_text=body)


def pg_sql(sql: str) -> str:
    pwd = env_file_value("POSTGRES_PASSWORD")
    cmd = ["docker", "exec", "-e", f"PGPASSWORD={pwd}", "-i", PG_CTR,
           "psql", "-U", "postgres", "-d", "judge_ai", "-v", "ON_ERROR_STOP=1", "-t", "-A", "-F", "|"]
    return sh(cmd, stdin_text=sql)


def pg_count(sql: str) -> int:
    """同 `count()`，但走 psql。用于影响面表里的 PG 行数。"""
    out = pg_sql(sql).strip()
    try:
        return int(out.split("\n")[-1])
    except ValueError:
        return -1


def disp_width(s: str) -> int:
    """终端显示宽度（CJK 占 2 列）。

    Python 的 `{:<38}` 按**码点数**补齐，中文名会短一截、表格右边缘参差 ——
    一个「整理对齐」的任务输出里出现歪掉的表，说不过去。
    """
    import unicodedata
    return sum(2 if unicodedata.east_asian_width(ch) in "WF" else 1 for ch in s)


def pad(s: str, w: int, align: str = "<") -> str:
    gap = " " * max(0, w - disp_width(s))
    return s + gap if align == "<" else gap + s


def redis_cli(*args) -> str:
    pwd = env_file_value("REDIS_PASSWORD")
    cmd = ["docker", "exec", "-i", REDIS_CTR, "redis-cli", "-a", pwd, "--no-auth-warning", *args]
    return sh(cmd, check=False)


def redis_count_prefix(prefix: str) -> int:
    """只数不删 —— 供预演的「影响面」表使用。

    预演必须给**真实数字**：一张写着 `redis judge:*  0 条` 的影响面表会让人
    直接按 `--yes`，而实际上有十几个键要没。宁可报错也不要假绿。
    """
    return len([k for k in redis_cli("--scan", "--pattern", prefix).split("\n") if k.strip()])


def redis_delete_prefix(prefix: str) -> int:
    keys = [k for k in redis_cli("--scan", "--pattern", prefix).split("\n") if k.strip()]
    deleted = 0
    for i in range(0, len(keys), 200):
        batch = keys[i:i + 200]
        out = redis_cli("DEL", *batch)
        try:
            deleted += int(out.strip().split("\n")[-1])
        except ValueError:
            pass
    return deleted


# ==========================================================================
# HTTP 辅助
# ==========================================================================
def login(phone: str, password: str, tries: int = 15) -> dict:
    """登录并返回 Authorization 头。

    ⚠️ 必须带退避重试：网关对 `POST /accounts/login` 挂了令牌桶
    （`login-rate-limit`，默认 replenishRate=2 / burstCapacity=5，见 gateway
    application.yml）。本脚本一个阶段要连登 6 个以上账号（5 学员 + 教师 + 管理员），
    第一次必然把桶打空。

    被打回时的**症状很有迷惑性**：不是带业务体的 429，而是一个空响应体，
    于是 `r.json()` 抛 `JSONDecodeError: Expecting value: line 1 column 1 (char 0)`
    —— 看起来像"服务挂了"或"路由配错"，实际只是限流。

    处理方式是**客户端守规矩地退避**（按 2/s 的回填速率等 0.8s 再试），
    而不是去放宽线上的安全水位：那是防爆破的控制，为了跑一次演示数据把它调松，
    等于把生产配置也带歪。
    """
    last = ""
    for i in range(tries):
        r = requests.post(f"{GATEWAY}/accounts/login",
                          json={"cellPhone": phone, "password": password}, timeout=TIMEOUT)
        try:
            data = r.json()
        except ValueError:
            # 空体 / 非 JSON：限流或网关瞬时不可用 → 退避后重试
            last = f"HTTP {r.status_code}，响应体 {r.text[:100]!r}"
            time.sleep(0.8)
            continue
        token = (data.get("data") or {}).get("accessToken")
        if token:
            return {"Authorization": f"Bearer {token}"}
        if r.status_code == 429 or data.get("code") == 429:
            last = json.dumps(data, ensure_ascii=False)[:200]
            time.sleep(0.8)
            continue
        # 业务失败（口令错、账号被锁等）不重试 —— 重试只会把账号往锁里推
        raise SystemExit(f"登录失败（{phone}）：{json.dumps(data, ensure_ascii=False)[:300]}")
    raise SystemExit(f"登录退避 {tries} 次仍被拒（{phone}）：{last}")


def submit(headers: dict, problem_id: int, language: str, code: str, contest_id: int = 0) -> dict:
    r = requests.post(f"{GATEWAY}/submissions", headers=headers,
                      json={"problemId": problem_id, "contestId": contest_id,
                            "language": language, "code": code}, timeout=TIMEOUT)
    return r.json()


def wait_terminal(headers: dict, sid: int, budget_s: float = 60.0) -> dict:
    """轮询到终态（status != PENDING/JUDGING）。返回详情 data。"""
    deadline = time.time() + budget_s
    last = {}
    while time.time() < deadline:
        r = requests.get(f"{GATEWAY}/submissions/{sid}", headers=headers, timeout=TIMEOUT)
        last = (r.json().get("data") or {})
        if last.get("status") not in ("PENDING", "JUDGING"):
            return last
        time.sleep(0.5)
    return last


def check(name: str, ok: bool, detail: str = "") -> bool:
    global PASS, FAIL
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" —— {detail}" if detail else ""))
    if ok:
        PASS += 1
    else:
        FAIL += 1
    return ok


def warn(msg: str):
    print(f"  [WARN] {msg}")


def resolve_demo_ids() -> dict:
    """按标题从库里取回三场演示竞赛的 id（找不到的 key 不出现在结果里）。

    标题是唯一稳定键（见 DEMO_TITLES 的说明）。返回 `{"ended": <id>, ...}`。
    """
    titles = list(DEMO_TITLES.values())
    inlist = ",".join("'" + t.replace("'", "''") + "'" for t in titles)
    out = mysql_sql(f"SELECT title, id FROM judge_contest.contest WHERE title IN ({inlist});")
    found = {}
    for line in out.strip().split("\n"):
        if "\t" not in line:
            continue
        title, cid = line.split("\t")[:2]
        for key, t in DEMO_TITLES.items():
            if t == title:
                found[key] = int(cid)
    return found


# ==========================================================================
# 阶段 1：备份
# ==========================================================================
def phase_backup(args):
    ts = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    out = ROOT / "backups" / ts
    if not args.yes:
        print(f"  将写入 {out.relative_to(ROOT)}/（mysql/ 5 个 .sql + pg/ 1 个 .sql，预计 2–3 MB）")
        return
    (out / "mysql").mkdir(parents=True, exist_ok=True)
    (out / "pg").mkdir(parents=True, exist_ok=True)
    pwd = env_file_value("MYSQL_ROOT_PASSWORD")
    for db in MYSQL_DBS:
        sql = sh(["docker", "exec", "-e", f"MYSQL_PWD={pwd}", "-i", MYSQL_CTR,
                  "mysqldump", "-uroot", "--default-character-set=utf8mb4", "--single-transaction",
                  "--databases", db])
        (out / "mysql" / f"{db}.sql").write_text(sql, encoding="utf-8")
        print(f"  ✓ mysql/{db}.sql  {len(sql.encode('utf-8')) // 1024} KB")
    ppwd = env_file_value("POSTGRES_PASSWORD")
    dump = sh(["docker", "exec", "-e", f"PGPASSWORD={ppwd}", "-i", PG_CTR,
               "pg_dump", "-U", "postgres", "-d", "judge_ai"])
    (out / "pg" / "judge_ai.sql").write_text(dump, encoding="utf-8")
    print(f"  ✓ pg/judge_ai.sql  {len(dump.encode('utf-8')) // 1024} KB")
    print(f"  备份完成：{out}（回滚方式：mysql < {db}.sql / psql < judge_ai.sql）")


# ==========================================================================
# 阶段 2：清洗
# ==========================================================================
JUNK_USER_WHERE = (
    "("
    "  cell_phone LIKE '1395000%'"          # 压测账号池（perf-test/seed-users.py 可重建）
    "  OR name LIKE '压测学员%'"
    "  OR name LIKE '验收临时%'"
    "  OR cell_phone = '13900000099'"       # P3 验收遗留管理员（文档已改为使用真实管理员）
    ")"
)


def count(expr_sql: str, db: str) -> int:
    out = mysql_sql(f"SELECT {expr_sql};", db=db).strip()
    try:
        return int(out.split("\n")[-1])
    except ValueError:
        return -1


def phase_clean(args):
    admin_phone = env_file_value("CJ_ADMIN_PHONE") or "13800000000"

    # 影响面（预演与实际都打印，实际执行前已由 --yes 授权）
    junk_users = count(f"COUNT(*) FROM `user` WHERE {JUNK_USER_WHERE}", "judge_user")
    print("  影响面：")
    rows = [
        ("judge_user.user", junk_users, f"压测/验收残留账号（保留 5 学员 + 2 教师 + {admin_phone}）"),
        ("judge_user.user_detail", -1, "孤儿详情（所属用户已被删的行）"),
        ("judge_submission.submission", count("COUNT(*) FROM submission", "judge_submission"), "全部提交"),
        ("judge_submission.judge_task", count("COUNT(*) FROM judge_task", "judge_submission"), "全部判题任务"),
        ("judge_submission.judge_result", count("COUNT(*) FROM judge_result", "judge_submission"), "全部用例结果"),
        ("judge_submission.compile_info", count("COUNT(*) FROM compile_info", "judge_submission"), "全部编译信息"),
        ("judge_auth.login_record", count("COUNT(*) FROM login_record", "judge_auth"), "全部登录记录"),
        ("judge_contest.contest", count("COUNT(*) FROM contest", "judge_contest"), "全部竞赛"),
        ("judge_contest.contest_problem", count("COUNT(*) FROM contest_problem", "judge_contest"), "竞赛题目编排"),
        ("judge_contest.contest_registration", count("COUNT(*) FROM contest_registration", "judge_contest"), "报名记录"),
        ("judge_contest.contest_rank_snapshot", count("COUNT(*) FROM contest_rank_snapshot", "judge_contest"), "榜单快照"),
        ("redis judge:*", redis_count_prefix("judge:*"), "残留运行时键（SSE 流、会话记忆、worker 心跳、榜单）"),
        ("pg judge_ai", pg_count("SELECT (SELECT COUNT(*) FROM knowledge_chunk) + (SELECT COUNT(*) FROM ai_review)"),
         "knowledge_chunk + ai_review 全表清空"),
        ("judge_problem.problem_version", "不动",
         "被 problem.current_version_id 指向（删了要改指针，收益为零）"),
    ]
    for name, n, why in rows:
        shown = n if isinstance(n, str) else (str(n) if n >= 0 else "?")
        unit = "" if isinstance(n, str) else " 条"
        print(f"    · {pad(name, 34)} {pad(shown, 6, '>')}{unit:<3} {why}")
    print("    · judge_problem.problem.submit_count / accepted_count → 归零（随后按真实提交回填）")

    if not args.yes:
        return

    print("  执行清洗……")
    mysql_sql(f"""
DELETE FROM judge_submission.judge_result;
DELETE FROM judge_submission.compile_info;
DELETE FROM judge_submission.judge_task;
DELETE FROM judge_submission.submission;
ALTER TABLE judge_submission.submission AUTO_INCREMENT = 1;
UPDATE judge_problem.problem SET submit_count = 0, accepted_count = 0;
-- 注意：这里**不动** problem_version。
--   ① problem.current_version_id 直接指向 problem_version.id（4001 → 第 5 版），
--      删行就要同步改指针，漏改会让题目详情页读不到题面；
--   ② 4001 确有冗余版本（v3/v4/v5 的 statement MD5 完全相同，是测试期反复保存留下的），
--      但它是"编辑历史"的自然产物、体量极小，且 /problems/{id}/versions 就靠它演示版本链。
--      清它的收益是 0，风险是非 0。故保留。
DELETE FROM judge_auth.login_record;
DELETE FROM judge_contest.contest_rank_snapshot;
DELETE FROM judge_contest.contest_registration;
DELETE FROM judge_contest.contest_problem;
DELETE FROM judge_contest.contest;
DELETE FROM judge_user.`user` WHERE {JUNK_USER_WHERE} AND cell_phone <> '{admin_phone}';
DELETE FROM judge_user.user_detail WHERE user_id NOT IN (SELECT id FROM judge_user.`user`);
""")
    left_users = count("COUNT(*) FROM `user`", "judge_user")
    print(f"  ✓ MySQL 清洗完成，user 表剩 {left_users} 行（5 学员 + 2 教师 + 1 管理员 = 8 为预期）")

    n = redis_delete_prefix("judge:*")
    print(f"  ✓ Redis 删除 judge:* 共 {n} 个键")

    pg_sql("TRUNCATE knowledge_chunk, ai_review RESTART IDENTITY;")
    print("  ✓ PostgreSQL 清空 knowledge_chunk / ai_review")


# ==========================================================================
# 阶段 3：灌入
# ==========================================================================
def pseudo_embedding(text: str, dim: int = 1024):
    """字符 bigram 词袋哈希伪向量（离线占位）。

    ⚠️ **这不是模型产出的向量。** 之所以需要它：本机 `CJ_LLM_ENABLED=false`，
    EmbeddingService 没有可用的上游，知识入库接口走不通 —— 但"AI 点评"这条链路的
    功能审查需要库里有切片。折中办法是给一段**确定的、可复现的**向量：
    字符 bigram 计数落到 1024 桶再做 L2 归一化，于是"用词相近的文本向量也相近"，
    检索有方向性、演示得起来。

    它**不能**用来评估检索质量（没有语义泛化能力，同义词视为不同词），
    接入真实 LLM 后应清空重灌（`--phases clean,seed` 即可）。
    """
    vec = [0.0] * dim
    t = "".join(ch for ch in (text or "").lower() if not ch.isspace())
    grams = [t[i:i + 2] for i in range(max(1, len(t) - 1))] or [t or "x"]
    for g in grams:
        h = int.from_bytes(hashlib.blake2b(g.encode("utf-8"), digest_size=8).digest(), "big")
        vec[h % dim] += 1.0
    norm = math.sqrt(sum(v * v for v in vec)) or 1.0
    return [v / norm for v in vec]


def vec_literal(v) -> str:
    return "[" + ",".join(f"{x:.6f}" for x in v) + "]"


KNOWLEDGE = [
    (4001, "EDITORIAL", "A+B 的整数溢出陷阱",
     "本题 a、b 均可达 2^31-1，两数之和可能超出 32 位有符号整数范围。"
     "用 int 相加会在隐藏用例上得到负数从而判 WA。改用 64 位整型（Java long / C++ long long / Python 原生大整数）即可通过。"
     "这类“输入范围看起来很小、结果却溢出”的题是初学者失败率最高的一类。"),
    (4002, "EDITORIAL", "1 到 n 求和：等差数列与时限约束",
     "n 可达 1e9，逐个累加需要 1e9 次加法，必然超出 1000ms 时限（实测 TLE）。"
     "应使用等差数列求和公式 n*(n+1)/2。注意中间结果要放在 64 位整型里，"
     "否则 n=1e9 时 n*(n+1) 约 1e18 会溢出。"),
    (4003, "ERROR_PATTERN", "内存受限题的常见错误模式",
     "本题内存上限仅 32MB。最常见的失败模式是把全部输入一次性读进数组："
     "n 达到 1e7 时，long 数组本身就要 80MB，容器会因超限被强制终止（MLE）。"
     "正确做法是边读边累加，空间复杂度 O(1)。"
     "另一个高频错误是在 Java 里用 Scanner 逐行读——它在大输入下既慢又占内存，"
     "应改用 StreamTokenizer 或 BufferedReader。"),
    (None, "TAG_NOTE", "堆优化 Dijkstra 的模板要点",
     "单源最短路用优先队列优化后复杂度为 O((n+m) log n)。实现要点："
     "① 距离数组初始化为无穷大，起点置 0；② 出队时若当前距离大于已记录的最短路则跳过（惰性删除，"
     "不要试图修改堆中已有元素）；③ 边权非负才可用；④ 起点与终点不连通时输出 -1。"
     "无向图存边时两个方向都要加。"),
    (None, "TAG_NOTE", "判题结论的含义与排查顺序",
     "AC=通过；WA=输出与期望不一致（先查边界与溢出）；TLE=超出时限（查复杂度）；"
     "MLE=超出内存（查是否整体落地）；RE=运行时错误（越界、除零、栈溢出）；"
     "CE=编译失败（看编译输出）；SE=系统错误（属基础设施问题，需看 worker 日志）。"
     "排查顺序建议：CE → RE → WA → TLE/MLE，因为前面的失败会掩盖后面的问题。"),
]


def phase_seed(args):
    print("  计划：")
    print("    · 建 3 场竞赛（按标题幂等：同名旧竞赛先清后建）："
          "已结束 / 进行中 / 未开始")
    print(f"    · 报名：{len(STUDENTS)} 名演示学员 × 3 场")
    print(f"    · 真实提交 {len(NON_CONTEST)} 条日常 + {len(CONTEST_ENDED)} 条（已结束场）"
          f" + {len(CONTEST_RUNNING)} 条（进行中场）= {len(NON_CONTEST) + len(CONTEST_ENDED) + len(CONTEST_RUNNING)} 条，"
          f"全部经网关投递、由判题机真判（预计 4–6 分钟）")
    print(f"    · 回填 problem.submit_count/accepted_count 与 contest_problem 计数")
    print(f"    · PostgreSQL：{len(KNOWLEDGE)} 条知识切片 + 3 条历史点评（含 1024 维占位向量）")
    if not args.yes:
        return

    if requests.get(f"{GATEWAY}/actuator/health", timeout=5, proxies={"http": None}).status_code >= 500:
        raise SystemExit("网关不可用，请先启动服务：python scripts/start-all.py --no-infra --wait")

    teacher = login(TEACHER_PHONE, STUDENT_PASS)
    now = dt.datetime.now()

    # ---- 3.1 建竞赛 ----
    # "已结束"那场的窗口参数是被 `Contest.frozenAt()` 的判定**反推**出来的，不是随手写的：
    #
    #   frozenAt(now) = freezeAt != null && now >= freezeAt && now < endTime
    #
    # 也就是说**封榜只在"已到封榜时刻、但赛程还没结束"时成立**。而 freeze_at 不是入参，
    # 是服务端按 `endTime - freezeMinutes` 算出来的。要让封榜在灌数据期间可得，必须满足：
    #     startTime < now < endTime     且     freezeAt = endTime - 30min <= now
    # 于是取 end_off = +10min、freezeMinutes = 30 → freezeAt = now - 20min。
    # 窗口给 +10min 是为了容下灌 55 条提交的 ~4 分钟（实测 235s），别在提交跑完前就结束。
    #
    # 早先这里写 end_off = +120、之后再用 SQL 把 end_time 改成"过去"并置 status=2 ——
    # 那等于**绕过生命周服务**：封榜快照（FROZEN）和终榜留档（FINAL）都由
    # ContestLifecycleService 每 10s 的扫描写，SQL 硬改状态让这两条路径都不会被触发，
    # 结果就是"已结束的竞赛没有封榜快照、也没有终榜快照"（verify 抓到的 2 个 FAIL）。
    # 现在改成：提交灌完后**先**在"仍在赛程内"时调 rebuild?refreeze=true 生成封榜快照，
    # 再把 end_time 推回过去、让扫描任务自己把状态推进到"已结束"并写终榜。
    plans = {
        "ended": (DEMO_TITLES["ended"],
                  "2 小时赛程，赛末 30 分钟封榜。演示终榜重建、封榜快照留档，以及"
                  "「解封后公开榜等于完整榜」。", -120, +10, 30, [4001, 4002, 4004]),
        "running": (DEMO_TITLES["running"],
                    "3 小时赛程，赛末 30 分钟封榜（当前未到封榜时刻）。"
                    "演示 Redis 实时榜与 WebSocket 名次推送。", -45, +180, 30, [4001, 4002, 4003]),
        "upcoming": (DEMO_TITLES["upcoming"],
                     "明天开赛。演示“未开始”状态与赛前报名。", +1440, +1620, 0, [4001, 4005]),
    }

    # 幂等：先把同名旧竞赛连编排/报名/快照一起清掉再重建，
    # 否则 `--phases seed` 每跑一次就多堆 3 场同名竞赛（title 上没有唯一约束）。
    stale = resolve_demo_ids()
    for key, old in stale.items():
        mysql_sql(f"""
DELETE FROM judge_contest.contest_rank_snapshot WHERE contest_id = {old};
DELETE FROM judge_contest.contest_registration WHERE contest_id = {old};
DELETE FROM judge_contest.contest_problem WHERE contest_id = {old};
DELETE FROM judge_contest.contest WHERE id = {old};
""")
    if stale:
        print(f"  · 已按标题清理 {len(stale)} 场旧竞赛（幂等重建）")

    labels = ["A", "B", "C", "D"]
    ids = {}
    for key, (title, desc, start_off, end_off, freeze, probs) in plans.items():
        body = {
            "title": title,
            "description": desc,
            "rule": "ACM",
            "startTime": (now + dt.timedelta(minutes=start_off)).strftime("%Y-%m-%dT%H:%M:%S"),
            "endTime": (now + dt.timedelta(minutes=end_off)).strftime("%Y-%m-%dT%H:%M:%S"),
            "freezeMinutes": freeze,
            "penaltyMinutes": 20,
            "problems": [{"problemId": p, "label": labels[i], "displayOrder": i, "fullScore": 100}
                         for i, p in enumerate(probs)],
        }
        r = requests.post(f"{GATEWAY}/contests", headers=teacher, json=body, timeout=TIMEOUT).json()
        if r.get("code") != 200:
            raise SystemExit(f"创建竞赛失败（{key}）：{json.dumps(r, ensure_ascii=False)[:300]}")
        cid = (r.get("data") or {}).get("id")
        if not cid:
            raise SystemExit(f"创建竞赛未返回 id（{key}）：{json.dumps(r, ensure_ascii=False)[:300]}")
        ids[key] = cid
        print(f"  ✓ 竞赛已创建：{title} → id={cid}")

    # ---- 3.2 报名（必须在竞赛结束之前）----
    for pid, phone, _name in STUDENTS:
        h = login(phone, STUDENT_PASS)
        for key in ("ended", "running", "upcoming"):
            r = requests.post(f"{GATEWAY}/contests/{ids[key]}/register",
                              headers=h, timeout=TIMEOUT).json()
            if r.get("code") != 200:
                warn(f"报名失败 user={pid} contest={ids[key]}：{r.get('msg')}")
    print(f"  ✓ 报名完成（{len(STUDENTS)} 名学员 × 3 场）")

    # ---- 3.3 真实提交 ----
    tokens = {pid: login(phone, STUDENT_PASS) for pid, phone, _n in STUDENTS}
    seq = 0

    def do_submit(user, problem, contest, lang, code_key):
        nonlocal seq
        seq += 1
        code = CODE[code_key]
        # nonce 注释：提交幂等键含 code_hash，同一 (用户,题目,竞赛) 上的多次提交
        # 若代码完全相同会命中 60s 幂等窗返回旧记录（等于没提交）。
        #
        # ⚠️ 行注释符号必须按语言选：早先除 JAVA 外一律用 `#`，
        #    于是每条 CPP 提交都得到 `# demo#3` —— C++ 里 `#` 是预处理指令，
        #    直接 `error: invalid preprocessing directive`；Go 里 `#` 非法字符。
        #    结果是**全部 CPP 与 GO 提交被判 CE**，而日志里只显示"结论与预期不符"，
        #    看起来像判题机坏了，其实是喂进去的代码自己编译不过。
        code = code + f"\n{NONCE_PREFIX[lang]} demo#{seq}"
        expect = code_key.split(":")[1]
        r = submit(tokens[user], problem, lang, code, contest)
        data = r.get("data") or {}
        if r.get("code") != 200 or not data.get("id"):
            warn(f"提交被拒 user={user} problem={problem} contest={contest}："
                 f"{json.dumps(r, ensure_ascii=False)[:160]}")
            return None
        detail = wait_terminal(tokens[user], data["id"])
        got = detail.get("verdict") or detail.get("status")
        ok = got == expect
        mark = "✓" if ok else "!"
        print(f"    {mark} #{seq:02d} u{user} p{problem} c{contest} {lang:<6} → {got:<4}"
              f"（期望 {expect}）{detail.get('timeMs') or '-'}ms")
        if not ok:
            warn(f"实际结论与预期不符：{code_key} → {got}（判题机才是权威，已按实际结果入库）")
        return got

    print("  开始真实提交（走网关 → MQ → 沙箱，预计 4–6 分钟）……")
    t0 = time.time()
    for user, problem, contest, lang, key, _off in NON_CONTEST:
        do_submit(user, problem, contest, lang, key)
    for user, problem, lang, key, _off in CONTEST_ENDED:
        do_submit(user, problem, ids["ended"], lang, key)
    for user, problem, lang, key, _off in CONTEST_RUNNING:
        do_submit(user, problem, ids["running"], lang, key)
    print(f"  ✓ 提交完成，耗时 {time.time() - t0:.0f}s")

    # ---- 3.4 提交时间回拨 ----
    # 上面每条都是"现在"提交的，于是 34 条日常提交的时间戳全挤在一起，
    # "提交时间"列看不出层次、也演示不了相对时间。这里按清单里的偏移量把 submit_time
    # 逐条改回去 —— **只改时间戳，不改结论**（结论是判题机真判出来的）。
    # 竞赛提交不回拨：它们必须留在竞赛窗口内，而窗口就是按"现在"设的。
    order = mysql_sql(
        "SELECT id FROM judge_submission.submission WHERE contest_id = 0 ORDER BY id;",
        "judge_submission").split()
    if len(order) != len(NON_CONTEST):
        warn(f"日常提交数与清单不一致（库 {len(order)} / 清单 {len(NON_CONTEST)}），跳过时间回拨")
    else:
        stmts = []
        for sid, (_u, _p, _c, _l, _k, off) in zip(order, NON_CONTEST):
            stmts.append(
                f"UPDATE judge_submission.submission SET submit_time = DATE_SUB(NOW(), INTERVAL "
                f"{-off} MINUTE), create_time = DATE_SUB(NOW(), INTERVAL {-off} MINUTE) WHERE id = {sid};")
        mysql_sql("\n".join(stmts))
        print(f"  ✓ 已把 {len(order)} 条日常提交的 submit_time 按计划偏移回拨")

    # ---- 3.5 「已结束」那场先不动 ----
    # 这里**故意不**用 SQL 把 status 改成 2。原因见 3.1 的说明：封榜快照（FROZEN）与
    # 终榜留档（FINAL）都由 ContestLifecycleService 的 10s 扫描写入，而扫描是根据
    # end_time 与 freeze_at 自己推导状态的。硬改 status 会让这两条路径都跑不到。
    # 正确顺序是「先在赛程内封榜 → 再把 end_time 推回过去让扫描推进状态」，
    # 这一步因此挪到了 rank 阶段（见 phase_rank）。

    # ---- 3.6 回填通过率计数 ----
    # ⚠️ 这一步**本不该存在**：`problem.submit_count/accepted_count` 在 judge-problem 里
    #    只在建题时被置 0（ProblemService.create 附近），此后**全仓库没有任何写入点** ——
    #    也就是说列表页的「通过率」列在真实运行中恒为「—」。
    #    这里是数据侧的补偿（按真实提交聚合回填），并未修掉代码缺陷。
    resync_counters()
    print("  ✓ 已按真实提交回填 problem / contest_problem 的提交数与通过数")

    # ---- 3.7 登录记录（时序真实性）----
    # 口径对齐 AccountService 的真实写入点（judge-auth/.../AccountService.java）：
    #   · login_type 1 = 用户端 / 2 = 管理端（按**账号是否员工**判定，不是"走了哪个端点"）；
    #   · 该表**只记成功登录**，没有 success 列 —— 早先这里按 `(…, success, …)` 写，
    #     会直接撞上 Unknown column（表里那一位其实是 ipv4）；
    #   · cell_phone 必须是账号本人的号码。早先所有行都填了管理员的号，
    #     于是界面上出现"学员的 user_id + 管理员的手机号"这种自相矛盾的行；
    #   · **id 必须显式给**。该表主键 `id bigint NOT NULL` 且**没有 AUTO_INCREMENT**
    #     （应用侧由 MyBatis-Plus 的雪花填充器赋值），裸 INSERT 会报
    #     `Field 'id' doesn't have a default value`。这里用一个固定前缀段位，
    #     与雪花 id（~2.1e18）不冲突，便于人工辨认这批是脚本造的历史记录。
    rows = []
    base_id = 7000000000000000000
    n = 0
    for i, (uid, phone, _n) in enumerate(STUDENTS):
        ip = f"192.168.1.{30 + i}"
        for d in range(6):
            ts = (now - dt.timedelta(minutes=17 * (i + 1) + d * 400)).strftime("%Y-%m-%d %H:%M:%S")
            rows.append(f"({base_id + n}, {uid}, '{phone}', '{ip}', 1, '{ts}', '{ts}', '{ts}')")
            n += 1
    mysql_sql("INSERT INTO judge_auth.login_record "
              "(id, user_id, cell_phone, ipv4, login_type, login_time, create_time, update_time) VALUES\n"
              + ",\n".join(rows) + ";")
    print(f"  ✓ 写入 {len(rows)} 条历史登录记录（另有登录接口实时写入的真实记录）")

    # ---- 3.8 PostgreSQL：知识切片 + 历史点评 ----
    pg_sql("TRUNCATE knowledge_chunk, ai_review RESTART IDENTITY;")
    for problem_id, source_type, title, content in KNOWLEDGE:
        pid = "NULL" if problem_id is None else str(problem_id)
        text = title + " " + content
        pg_sql(f"INSERT INTO knowledge_chunk (problem_id, source_type, title, content, embedding) VALUES "
               f"({pid}, '{source_type}', $${title}$$, $${content}$$, '{vec_literal(pseudo_embedding(text))}'::vector);")
    print(f"  ✓ 写入 {len(KNOWLEDGE)} 条知识切片（向量为 bigram 哈希占位，非模型产出）")

    # 历史点评：挂在真实 AC 提交上，content 用**降级模式的实际文案**
    # （LlmClient.mockReply 的原文），这样"未配置 LLM"状态下界面展示的与真实产物一致。
    ac_rows = mysql_sql("""
SELECT s.id, s.user_id, s.problem_id, s.verdict FROM judge_submission.submission s
 WHERE s.verdict IN ('AC','WA') AND s.contest_id = 0 ORDER BY s.id LIMIT 3;
""", "judge_submission").strip().split("\n")
    mock_text = ("【CodeJudge AI 代码点评】当前未配置大模型 API Key（CJ_LLM_ENABLED=false 或 "
                 "CJ_LLM_API_KEY 为空），本段为占位输出。\n\n请在 .env 中配置 CJ_LLM_API_KEY 并将 "
                 "CJ_LLM_ENABLED 置为 true 后重试，即可获得基于题目知识库与历史点评的真实代码点评。")
    n_rev = 0
    for line in ac_rows:
        parts = line.split("\t") if "\t" in line else line.split()
        if len(parts) < 4:
            continue
        sid, uid, pid, verdict = int(parts[0]), int(parts[1]), int(parts[2]), parts[3]
        pg_sql(f"INSERT INTO ai_review (submission_id, user_id, problem_id, review_type, model, verdict, "
               f"content, status, embedding) VALUES ({sid}, {uid}, {pid}, 1, 'degraded', '{verdict}', "
               f"$${mock_text}$$, 1, '{vec_literal(pseudo_embedding(mock_text))}'::vector);")
        n_rev += 1
    print(f"  ✓ 写入 {n_rev} 条历史点评（降级文案，挂在真实提交上）")


def resync_counters():
    """按真实提交回填通过率计数（见 phase_seed 里对该缺陷的说明）。"""
    mysql_sql("""
UPDATE judge_problem.problem p
  LEFT JOIN (SELECT problem_id, COUNT(*) c, SUM(verdict = 'AC') a
               FROM judge_submission.submission WHERE status = 'SUCCESS' GROUP BY problem_id) s
    ON s.problem_id = p.id
   SET p.submit_count = COALESCE(s.c, 0), p.accepted_count = COALESCE(s.a, 0);
UPDATE judge_contest.contest_problem cp
  LEFT JOIN (SELECT contest_id, problem_id, COUNT(*) c, SUM(verdict = 'AC') a
               FROM judge_submission.submission
              WHERE status = 'SUCCESS' AND contest_id > 0 GROUP BY contest_id, problem_id) s
    ON s.contest_id = cp.contest_id AND s.problem_id = cp.problem_id
   SET cp.submit_count = COALESCE(s.c, 0), cp.accepted_count = COALESCE(s.a, 0);
""")


# ==========================================================================
# 阶段 4：榜单
# ==========================================================================
def phase_rank(args):
    ids = resolve_demo_ids()
    print("  榜单与快照：")
    print("    ① 进行中那场：POST /contests/{id}/rank/rebuild（不封榜）")
    print("    ② 已结束那场：**先在赛程内** rebuild?refreeze=true 生成封榜快照（FROZEN）")
    print("    ③ 再把它的 end_time 推回过去，交给 10s 扫描任务推进状态并写终榜（FINAL）")
    for key, cid in sorted(ids.items()):
        print(f"       · {key:<9} id={cid}")
    if not ids:
        raise SystemExit("库里没有演示竞赛，无法重建榜单；请先执行：--phases clean,seed")
    if not args.yes:
        return
    admin_phone = env_file_value("CJ_ADMIN_PHONE") or "13800000000"
    admin_pass = env_file_value("CJ_ADMIN_INIT_PASSWORD") or STUDENT_PASS
    admin = login(admin_phone, admin_pass)

    def rebuild(cid: int, refreeze: bool) -> dict:
        r = requests.post(f"{GATEWAY}/contests/{cid}/rank/rebuild?refreeze={'true' if refreeze else 'false'}",
                          headers=admin, timeout=60).json()
        if r.get("code") != 200:
            warn(f"榜单重建失败 contest={cid}：{json.dumps(r, ensure_ascii=False)[:200]}")
            return {}
        d = r.get("data") or {}
        print(f"  ✓ rebuilt id={cid}：fetched={d.get('fetched')} replayed={d.get('replayed')} "
              f"participants={d.get('participants')} refrozen={d.get('refrozen')} {d.get('tookMs')}ms")
        return d

    # ① / ② 先把两场的榜单（在赛程内）重算出来；"已结束"那场顺带封榜
    for key in ("running", "ended"):
        if key in ids:
            rebuild(ids[key], refreeze=(key == "ended"))

    # ③ 把"已结束"那场的结束时刻推回过去。**只改 end_time，不改 status** ——
    #    状态推进与终榜留档都由 ContestLifecycleService 每 10s 的扫描完成，
    #    这正是要演示的真实链路。手动把 status 置 2 会跳过它。
    if "ended" in ids:
        ended = ids["ended"]
        mysql_sql(f"UPDATE judge_contest.contest SET end_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE) "
                  f"WHERE id = {ended};")
        print(f"  · 已把竞赛 {ended} 的 end_time 推回过去，等待扫描任务推进到「已结束」并写终榜……")
        snap = 0
        st = ""
        for i in range(24):                     # 最多等 ~96s（扫描周期 10s，留足余量）
            time.sleep(4)
            st = mysql_sql(f"SELECT status FROM judge_contest.contest "
                           f"WHERE id = {ended} AND deleted = 0;",
                           "judge_contest").strip().split("\n")[-1].strip()
            snap = int(mysql_sql("SELECT COUNT(*) FROM judge_contest.contest_rank_snapshot "
                                 "WHERE deleted = 0 AND snapshot_type = 'FINAL';",
                                 "judge_contest").strip().split("\n")[-1])
            if st == "2" and snap > 0:
                break
        ok = st == "2" and snap > 0
        print(f"  {'✓' if ok else '!'} 竞赛 {ended}：status={st}（期望 2）终榜快照={snap} 条（期望 >0）")
        if not ok:
            warn("扫描任务未在 96s 内完成推进；可稍后重跑 `--yes --phases rank,verify` 再验")

    # ④ 未开始那场没有提交，重建一次把空榜落定（也验证 cid 有效）
    if "upcoming" in ids:
        rebuild(ids["upcoming"], refreeze=False)


# ==========================================================================
# 阶段 5：校验
# ==========================================================================
def phase_verify(args):
    print("  交叉校验（这些断言会同时校验样本非空 —— 零样本时 all() 恒真，属假绿）")
    print("  注：校验是**只读**的，预演模式下同样会跑，下面的结果描述的是**当前库里**的实际状态，"
          "不是对本次执行结果的预测。")

    # ⚠️ **所有计数都必须带 `deleted = 0`。**
    #    本项目的 PO 全部继承 BasePO，其 `deleted` 字段标了 `@TableLogic`
    #    （`judge-contest/.../application.yml` 也配了 logic-delete-field）——
    #    也就是说 MyBatis 侧看到的"行"是**过滤掉软删后的行**，而裸 SQL 的 COUNT(*) 会把
    #    软删行也算进去。首版 verify 正是栽在这里：`contest_rank_snapshot` 被 refreeze
    #    软删了 1 行（deleted=1），裸 SQL 报 3 条、应用只看得到 2 条；
    #    更糟的是若快照**全部**被软删，`> 0` 这个断言**照样 PASS** —— 又是一条假绿。
    #    规则：断言"应用能看到什么"，就必须按应用的口径（deleted=0）来数。
    def scalar(sql, db):
        out = mysql_sql(sql, db).strip()
        return out.split("\n")[-1].strip() if out else ""

    subs = int(scalar("SELECT COUNT(*) FROM submission WHERE deleted = 0;", "judge_submission") or 0)
    tasks = int(scalar("SELECT COUNT(*) FROM judge_task WHERE deleted = 0;", "judge_submission") or 0)
    results = int(scalar("SELECT COUNT(*) FROM judge_result WHERE deleted = 0;", "judge_submission") or 0)
    check("提交记录非空", subs > 0, f"{subs} 条")
    check("每条提交都有判题任务", subs > 0 and subs == tasks, f"submission={subs} judge_task={tasks}")
    check("存在逐用例结果（详情页有内容可看）", results > subs, f"{results} 行 / {subs} 条提交")

    kinds = mysql_sql("SELECT DISTINCT verdict FROM submission WHERE deleted = 0 AND status='SUCCESS' "
                      "AND verdict IS NOT NULL ORDER BY verdict;", "judge_submission").split()
    missing = [v for v in ALLOWED_VERDICTS if v not in kinds]
    check("六种判题结论齐备", not missing, f"实际={kinds}" + (f" 缺={missing}" if missing else ""))

    # ⚠️ 这里曾有一句 `bad = scalar("""… JOIN problem p …""", "judge_submission")`，
    #    但 `mysql -uroot judge_submission` 的默认库是 judge_submission，
    #    `problem` 会被解析成 `judge_submission.problem` → Unknown table，
    #    mysql_sql 的 check=True 直接 SystemExit，**整个 verify 阶段会挂在这里**。
    #    下面的写法把两张表都写成全限定名，一次 JOIN 到位。
    mism = mysql_sql("""
SELECT COUNT(*) FROM judge_problem.problem p
  LEFT JOIN (SELECT problem_id, COUNT(*) c, SUM(verdict='AC') a FROM judge_submission.submission
              WHERE deleted = 0 AND status='SUCCESS' GROUP BY problem_id) s ON s.problem_id = p.id
 WHERE p.deleted = 0 AND (p.submit_count <> COALESCE(s.c,0) OR p.accepted_count <> COALESCE(s.a,0));
""").strip().split("\n")[-1]
    check("通过率计数与真实提交一致", mism == "0", f"不一致行数={mism}")

    users = int(scalar("SELECT COUNT(*) FROM `user` WHERE deleted = 0;", "judge_user") or 0)
    check("账号表只剩基线账号（5 学员 + 2 教师 + 1 管理员）", users == 8, f"user={users}")

    ids = resolve_demo_ids()
    check("三场演示竞赛齐备（按标题匹配）", len(ids) == 3, f"{sorted(ids)}")
    states = {k: scalar(f"SELECT status FROM judge_contest.contest "
                        f"WHERE id = {cid} AND deleted = 0;", "judge_contest")
              for k, cid in ids.items()}
    check("含一场已结束（status=2）", any(v == "2" for v in states.values()), f"{states}")

    pgc = pg_sql("SELECT (SELECT COUNT(*) FROM knowledge_chunk), (SELECT COUNT(*) FROM ai_review);").strip()
    chunk_n, review_n = (int(x) for x in pgc.split("|"))
    check("知识切片非空", chunk_n > 0, f"{chunk_n} 条")
    check("历史点评非空", review_n > 0, f"{review_n} 条")

    # 榜单键名以 JudgeRedisKeys 为准（早先这里写成 `…:rank:live:{cid}`，
    # 库里没这个键 → ZCARD 恒为 0，断言永远"通过"或永远"失败"，两种都是错的）。
    #   · 实时榜  judge:contest:rank:{cid}
    #   · 冻结榜  judge:contest:rank:frozen:{cid}（封榜瞬间 ZUNIONSTORE 生成）
    zcards = []
    for key, cid in sorted(ids.items()):
        live = redis_cli("ZCARD", f"judge:contest:rank:{cid}").strip()
        frozen = redis_cli("ZCARD", f"judge:contest:rank:frozen:{cid}").strip()
        zcards.append((key, cid, live, frozen))
    for key, cid, live, frozen in zcards:
        print(f"    · contest={cid}（{key}）实时榜={live} 冻结榜={frozen}")
    active = [(k, live) for k, c, live, _f in zcards if k in ("ended", "running")]
    check("已结束/进行中两场的实时榜都有数据",
          all(z.isdigit() and int(z) > 0 for _k, z in active), f"{active}")
    ended_frozen = [f for k, c, _l, f in zcards if k == "ended"]
    check("已结束那场存在封榜冻结榜（refreeze 产物）",
          bool(ended_frozen) and ended_frozen[0].isdigit() and int(ended_frozen[0]) > 0,
          f"frozen={ended_frozen}")
    # ⚠️ 这里**必须**带 `deleted = 0`：refreeze 会软删旧 FROZEN 快照，裸 COUNT(*)
    #    会把软删行算进来（曾实测裸数 3 条 / 应用只看得到 2 条）。更糟的是
    #    「快照全被软删」时 `> 0` 仍然成立 —— 那是一条假绿断言。
    snaps = scalar("SELECT COUNT(*) FROM contest_rank_snapshot WHERE deleted = 0;", "judge_contest")
    check("榜单快照已落库（不依赖 Redis 存活）", snaps.isdigit() and int(snaps) > 0, f"{snaps} 条")


# ==========================================================================
def main():
    ap = argparse.ArgumentParser(description="CodeJudge 演示数据重置（默认预演，加 --yes 落盘）")
    ap.add_argument("--yes", action="store_true", help="真正执行（默认只预演）")
    ap.add_argument("--phases", default="backup,clean,seed,rank,verify",
                    help="要执行的阶段，逗号分隔：backup,clean,seed,rank,verify")
    ap.add_argument("--skip-backup", action="store_true", help="跳过备份（已有本次备份时）")
    args = ap.parse_args()

    phases = [p.strip() for p in args.phases.split(",") if p.strip()]
    if args.skip_backup and "backup" in phases:
        phases.remove("backup")

    print("=" * 78)
    print(f"CodeJudge 演示数据重置　模式：{'执行' if args.yes else '预演（不改动任何数据）'}")
    print(f"阶段：{' → '.join(phases)}")
    print("=" * 78)

    fn = {"backup": phase_backup, "clean": phase_clean, "seed": phase_seed,
          "rank": phase_rank, "verify": phase_verify}
    for p in phases:
        if p not in fn:
            raise SystemExit(f"未知阶段：{p}（可选 {'/'.join(fn)}）")
        print(f"\n── 阶段 {p} " + "─" * (60 - len(p)))
        fn[p](args)

    if "verify" in phases:
        print(f"\n校验结果：PASS={PASS} FAIL={FAIL}")
    if not args.yes:
        print("\n以上为预演。确认无误后加 --yes 执行。")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
