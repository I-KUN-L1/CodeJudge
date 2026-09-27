-- ============================================================
-- CodeJudge 种子数据（幂等，可反复执行）
--
-- 与 sql/init.sql 分离的原因：init.sql 只在 MySQL 数据卷为空时被执行一次，
-- 而种子数据需要在联调过程中反复重放（例如删库重灌、换机器）。
-- 单独的 seed.sql 可以随时手工灌入：
--   docker exec -i codejudge-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" < sql/seed.sql
--
-- 全部使用 INSERT IGNORE，依赖唯一键去重，重复执行不产生副作用。
-- ============================================================

-- ------------------------------------------------------------------
-- 必须首先执行：显式声明会话字符集（原因见 sql/init.sql 顶部同段注释）。
-- 容器内 `docker exec mysql < file.sql` 在 LANG 未设置时客户端字符集会退化为
-- latin1，把 UTF-8 字节双重编码：'数组' 存成 'æ•°ç»„'，CHAR_LENGTH 2 → 6。
-- 本行让脚本自身免疫载入方式，不必依赖调用者加 --default-character-set=utf8mb4。
-- ------------------------------------------------------------------
SET NAMES utf8mb4;

-- ===================== 默认账号（密码均为 123456） =====================
-- BCrypt hash of '123456': $2a$10$OuwRvnFKhKxYDdlndTfjXOzhWRnUF6jTJ6xZEnFlQHkAwcud6rELG
--
-- 注意：**管理员账号不在本文件内**。首个管理员由 judge-auth 启动时的安全引导生成
-- （凭据写入 .bootstrap-credentials，首次改密后自动删除），避免把管理员凭据硬编码进仓库。
USE `judge_user`;

-- 学员（type=2）：用于提交代码、参加竞赛
INSERT IGNORE INTO `user` (`id`, `cell_phone`, `username`, `password`, `name`, `type`, `status`, `create_time`, `update_time`, `deleted`) VALUES
(2001, '13900000001', 'student001', '$2a$10$OuwRvnFKhKxYDdlndTfjXOzhWRnUF6jTJ6xZEnFlQHkAwcud6rELG', '演示学员一', 2, 1, NOW(), NOW(), 0),
(2002, '13900000002', 'student002', '$2a$10$OuwRvnFKhKxYDdlndTfjXOzhWRnUF6jTJ6xZEnFlQHkAwcud6rELG', '演示学员二', 2, 1, NOW(), NOW(), 0),
(2003, '13900000003', 'student003', '$2a$10$OuwRvnFKhKxYDdlndTfjXOzhWRnUF6jTJ6xZEnFlQHkAwcud6rELG', '演示学员三', 2, 1, NOW(), NOW(), 0),
(2004, '13900000004', 'student004', '$2a$10$OuwRvnFKhKxYDdlndTfjXOzhWRnUF6jTJ6xZEnFlQHkAwcud6rELG', '演示学员四', 2, 1, NOW(), NOW(), 0),
(2005, '13900000005', 'student005', '$2a$10$OuwRvnFKhKxYDdlndTfjXOzhWRnUF6jTJ6xZEnFlQHkAwcud6rELG', '演示学员五', 2, 1, NOW(), NOW(), 0);

-- 教师（type=3）：用于创建题目、查看隐藏用例、组织竞赛
-- 教师账号不开放自助注册（见 TeacherController 的安全加固说明），仅能由员工/管理员开通
INSERT IGNORE INTO `user` (`id`, `cell_phone`, `username`, `password`, `name`, `type`, `status`, `create_time`, `update_time`, `deleted`) VALUES
(2101, '13900000011', 'teacher001', '$2a$10$OuwRvnFKhKxYDdlndTfjXOzhWRnUF6jTJ6xZEnFlQHkAwcud6rELG', '演示教师一', 3, 1, NOW(), NOW(), 0),
(2102, '13900000012', 'teacher002', '$2a$10$OuwRvnFKhKxYDdlndTfjXOzhWRnUF6jTJ6xZEnFlQHkAwcud6rELG', '演示教师二', 3, 1, NOW(), NOW(), 0);

-- 教师详情（职称/学校/简介，供教师主页与题目归属展示）
INSERT IGNORE INTO `user_detail` (`id`, `user_id`, `job_title`, `intro`, `school`, `create_time`, `update_time`, `deleted`) VALUES
(210101, 2101, '高级讲师', '十年一线研发与算法教学经验，主讲数据结构与算法竞赛。', 'CodeJudge 演示大学', NOW(), NOW(), 0),
(210201, 2102, '算法教练', 'ACM/ICPC 区域赛教练，专注动态规划与图论专题。', 'CodeJudge 演示大学', NOW(), NOW(), 0);

-- ===================== 题目标签 =====================
USE `judge_problem`;

INSERT IGNORE INTO `tag` (`id`, `name`, `type`, `create_time`, `update_time`, `deleted`) VALUES
(3001, '数组', 'ALGORITHM', NOW(), NOW(), 0),
(3002, '字符串', 'ALGORITHM', NOW(), NOW(), 0),
(3003, '哈希表', 'ALGORITHM', NOW(), NOW(), 0),
(3004, '排序', 'ALGORITHM', NOW(), NOW(), 0),
(3005, '二分查找', 'ALGORITHM', NOW(), NOW(), 0),
(3006, '动态规划', 'ALGORITHM', NOW(), NOW(), 0),
(3007, '贪心', 'ALGORITHM', NOW(), NOW(), 0),
(3008, '图论', 'ALGORITHM', NOW(), NOW(), 0),
(3009, '数学', 'ALGORITHM', NOW(), NOW(), 0),
(3010, '模拟', 'ALGORITHM', NOW(), NOW(), 0);

-- ===================== P2：题目 / 题面版本 / 用例 / 标签关联 =====================
--
-- 固定 id 段位（避免与雪花 id 冲突，也便于联调时直接引用）：
--   problem          4001–4006
--   problem_version  4101–4106（与 problem 一一对应，version_no = 1）
--   test_case        4201–4217
--   problem_tag      4301–4312
--
-- 六种判题结论的可复现设计（P3 判题链路就绪后即可逐条验证）：
--   AC  —— 正确解法（如 4001 用公式直接相加）
--   WA  —— 4001 的 seq3 输入 2147483647+2147483647，用 int 相加会溢出得到负数
--   TLE —— 4002 n 可达 1e9，朴素 for 循环必超 1s；需 O(1) 公式
--   MLE —— 4003 内存限制压到 32MB，n=1e7 时一次性读入数组即超限
--   RE  —— 4004 含 n=0 / 访问下标 n 的边界用例，越界或除零直接非零退出
--   CE  —— 与题目内容无关，提交语法非法的代码即触发（编译阶段先于用例执行）
--
-- template_code 是 JSON 列：换行必须写成 \\n（两个字符 \ 与 n），
--    MySQL 会解析成 JSON 的合法转义序列；若只写 \n，MySQL 会替换成真实换行，
--    而 JSON 字符串内不允许裸换行，插入会直接报 3140 Invalid JSON text。

INSERT IGNORE INTO `problem`
(`id`, `title`, `difficulty`, `time_limit_ms`, `memory_limit_mb`, `status`, `owner_id`, `current_version_id`, `submit_count`, `accepted_count`, `create_time`, `update_time`, `deleted`) VALUES
-- 已发布：覆盖 AC / WA
(4001, 'A + B Problem', 1, 1000, 256, 1, 2101, 4101, 0, 0, NOW(), NOW(), 0),
-- 已发布：覆盖 TLE（朴素循环超时）
(4002, '1 到 n 求和', 2, 1000, 256, 1, 2101, 4102, 0, 0, NOW(), NOW(), 0),
-- 已发布：覆盖 MLE（内存限制收紧到 32MB）
(4003, '大数组求和（内存受限）', 3, 2000, 32, 1, 2101, 4103, 0, 0, NOW(), NOW(), 0),
-- 已发布：覆盖 RE（边界用例）
(4004, '数组访问边界', 3, 1000, 256, 1, 2102, 4104, 0, 0, NOW(), NOW(), 0),
-- 已发布：覆盖 CE（要求完整程序结构，提交语法错误代码即编译失败）
(4005, '最短路上机综合题', 4, 2000, 256, 1, 2102, 4105, 0, 0, NOW(), NOW(), 0),
-- 草稿（status=0）：用于验证"学员看不到未发布题目"这条可见性规则，勿改成 1
(4006, '【草稿】可见性验证题', 2, 1000, 256, 0, 2102, 4106, 0, 0, NOW(), NOW(), 0);

-- 题面 v1（改题会新增 version_no=2 的记录，这里只给初始版本）
INSERT IGNORE INTO `problem_version`
(`id`, `problem_id`, `version_no`, `statement`, `input_spec`, `output_spec`, `hint`, `template_code`, `created_by`, `create_time`, `update_time`, `deleted`) VALUES
(4101, 4001, 1,
 '给定两个整数 a 和 b，输出它们的和。',
 '一行，两个以空格分隔的整数 a、b（-2^31 ≤ a, b ≤ 2^31-1）。',
 '一行，一个整数，表示 a + b 的结果。',
 '注意 a + b 可能超出 32 位有符号整数范围，请使用 64 位整型。',
 '{"java":"import java.util.*;\\npublic class Main{\\n  public static void main(String[] args){\\n    Scanner sc = new Scanner(System.in);\\n    long a = sc.nextLong(), b = sc.nextLong();\\n    System.out.println(a + b);\\n  }\\n}","python":"a, b = map(int, input().split()); print(a + b)","cpp":"#include <iostream>\\nusing namespace std;\\nint main(){ long long a,b; cin>>a>>b; cout<<a+b<<endl; return 0; }"}',
 2101, NOW(), NOW(), 0),

(4102, 4002, 1,
 '给定一个正整数 n，输出 1 + 2 + ... + n 的值。',
 '一行，一个正整数 n（1 ≤ n ≤ 10^9）。',
 '一行，一个整数，表示求和结果。',
 'n 最大到 10^9，逐个累加无法在时限内完成，请使用等差数列求和公式。',
 '{"java":"import java.util.*;\\npublic class Main{\\n  public static void main(String[] args){\\n    long n = new Scanner(System.in).nextLong();\\n    System.out.println(n * (n + 1) / 2);\\n  }\\n}","python":"n = int(input()); print(n * (n + 1) // 2)","cpp":"#include <iostream>\\nusing namespace std;\\nint main(){ long long n; cin>>n; cout<<n*(n+1)/2<<endl; return 0; }"}',
 2101, NOW(), NOW(), 0),

(4103, 4003, 1,
 '给定 n 个整数，输出它们的和。本题内存限制较紧，请注意空间开销。',
 '第一行一个整数 n（1 ≤ n ≤ 10^7）；第二行 n 个整数。',
 '一行，一个整数，表示这 n 个数的和。',
 '内存上限仅 32MB，若一次性把所有数读入数组会超出限制；可边读边累加。',
 '{"java":"import java.io.*;\\npublic class Main{\\n  public static void main(String[] args) throws Exception{\\n    StreamTokenizer in = new StreamTokenizer(new BufferedInputStream(System.in));\\n    in.nextToken(); int n = (int) in.nval;\\n    long sum = 0;\\n    for (int i = 0; i < n; i++){ in.nextToken(); sum += (long) in.nval; }\\n    System.out.println(sum);\\n  }\\n}","python":"n = int(input()); print(sum(map(int, input().split())))"}',
 2101, NOW(), NOW(), 0),

(4104, 4004, 1,
 '给定 n 个整数，输出它们的最大值与最小值的差。注意 n 可能为 1。',
 '第一行一个整数 n（1 ≤ n ≤ 10^5）；第二行 n 个整数。',
 '一行，一个整数，表示最大值与最小值之差。',
 'n = 1 时最大值与最小值相同，差为 0；数组下标从 0 开始，访问 arr[n] 会越界。',
 '{"java":"import java.util.*;\\npublic class Main{\\n  public static void main(String[] args){\\n    Scanner sc = new Scanner(System.in);\\n    int n = sc.nextInt();\\n    long min = Long.MAX_VALUE, max = Long.MIN_VALUE;\\n    for (int i = 0; i < n; i++){ long v = sc.nextLong(); min = Math.min(min, v); max = Math.max(max, v); }\\n    System.out.println(max - min);\\n  }\\n}","python":"n = int(input()); xs = list(map(int, input().split())); print(max(xs) - min(xs))"}',
 2102, NOW(), NOW(), 0),

(4105, 4005, 1,
 '给定一张 n 个点 m 条边的无向带权图，求从 1 号点到 n 号点的最短距离，不可达输出 -1。请提交完整可编译的程序。',
 '第一行两个整数 n、m（1 ≤ n ≤ 10^5，0 ≤ m ≤ 2×10^5）；随后 m 行每行三个整数 u、v、w 表示一条边。',
 '一行，一个整数，表示最短距离；若不可达输出 -1。',
 '请使用堆优化 Dijkstra，注意边权可能为 0 但不会为负。',
 '{"java":"import java.util.*;\\npublic class Main{\\n  public static void main(String[] args){\\n    Scanner sc = new Scanner(System.in);\\n    // TODO: 堆优化 Dijkstra\\n  }\\n}","cpp":"#include <bits/stdc++.h>\\nusing namespace std;\\nint main(){ /* TODO: heap dijkstra */ }"}',
 2102, NOW(), NOW(), 0),

(4106, 4006, 1,
 '【草稿】本题用于验证可见性：学员不应在列表与详情中看到它。',
 '无',
 '无',
 '仅管理员与题目归属教师可见。',
 '{"python":"print(0)"}',
 2102, NOW(), NOW(), 0);

-- 测试用例：is_hidden=0 为样例（对学员下发），is_hidden=1 为隐藏用例（仅归属教师/管理员可见）
INSERT IGNORE INTO `test_case`
(`id`, `problem_id`, `seq`, `stdin`, `expected_stdout`, `is_hidden`, `score`, `time_limit_ms`, `judge_mode`, `create_time`, `update_time`, `deleted`) VALUES
-- 4001 A+B：样例 2 个 + 隐藏 2 个（溢出用例是 WA 的关键）
(4201, 4001, 1, '1 2', '3', 0, 10, NULL, 0, NOW(), NOW(), 0),
(4202, 4001, 2, '100 200', '300', 0, 10, NULL, 0, NOW(), NOW(), 0),
(4203, 4001, 3, '2147483647 2147483647', '4294967294', 1, 40, NULL, 0, NOW(), NOW(), 0),
(4204, 4001, 4, '-1000000000 -1000000000', '-2000000000', 1, 40, NULL, 0, NOW(), NOW(), 0),
-- 4002 求和：隐藏大 n 用例用于触发 TLE
(4205, 4002, 1, '10', '55', 0, 10, NULL, 0, NOW(), NOW(), 0),
(4206, 4002, 2, '1000000000', '500000000500000000', 1, 50, NULL, 0, NOW(), NOW(), 0),
(4207, 4002, 3, '1', '1', 1, 40, NULL, 0, NOW(), NOW(), 0),
-- 4003 内存受限：大数据量用例用于触发 MLE
(4208, 4003, 1, '3\n1 2 3', '6', 0, 10, NULL, 0, NOW(), NOW(), 0),
-- ⚠️ 修正（2026-09-23）：本行原为 expected_stdout='10000000'（恰等于声明的 n），与 stdin 自相矛盾 ——
--    stdin 声明 n=10000000 却只给了 10 个 1，任何**正确**解法（跳过 n、读完其余数求和）都得 10，
--    于是被判 WA，即"4003 在本题数据下无解"。想按原意补足 1e7 个数字也不行：
--    `test_case.stdin` 是 `text`（上限 64KB），1e7 个数字约 20MB 根本存不下。
--    而 MLE 演示并不依赖这行输入的长度：MLE 片段是**按声明的 n 硬分配**大数组
--    （如 vector<long long>(8e6) = 64MB），输入只有 28 字节也照样超限。
--    故把期望改为实际输入的和（10），语义明确为「声明量与实供量不一致」的健壮性用例。
(4209, 4003, 2, '10000000\n1 1 1 1 1 1 1 1 1 1', '10', 1, 45, NULL, 0, NOW(), NOW(), 0),
(4210, 4003, 3, '1\n-5', '-5', 1, 45, NULL, 0, NOW(), NOW(), 0),
-- 4004 边界：n=1 与单元素用例用于触发 RE / 边界 WA
(4211, 4004, 1, '3\n1 5 3', '4', 0, 10, NULL, 0, NOW(), NOW(), 0),
(4212, 4004, 2, '1\n7', '0', 1, 45, NULL, 0, NOW(), NOW(), 0),
(4213, 4004, 3, '5\n-3 -1 -7 -2 -9', '8', 1, 45, NULL, 0, NOW(), NOW(), 0),
-- 4005 综合题
(4214, 4005, 1, '2 1\n1 2 5', '5', 0, 20, NULL, 0, NOW(), NOW(), 0),
(4215, 4005, 2, '3 0', '-1', 1, 40, NULL, 0, NOW(), NOW(), 0),
(4216, 4005, 3, '3 2\n1 2 1\n2 3 1', '2', 1, 40, NULL, 0, NOW(), NOW(), 0),
-- 4006 草稿题（仅 1 个样例）
(4217, 4006, 1, '0', '0', 0, 100, NULL, 0, NOW(), NOW(), 0);

-- 题目-标签关联
INSERT IGNORE INTO `problem_tag`
(`id`, `problem_id`, `tag_id`, `create_time`, `update_time`, `deleted`) VALUES
(4301, 4001, 3009, NOW(), NOW(), 0),
(4302, 4001, 3010, NOW(), NOW(), 0),
(4303, 4002, 3009, NOW(), NOW(), 0),
(4304, 4002, 3010, NOW(), NOW(), 0),
(4305, 4003, 3001, NOW(), NOW(), 0),
(4306, 4003, 3010, NOW(), NOW(), 0),
(4307, 4004, 3001, NOW(), NOW(), 0),
(4308, 4004, 3010, NOW(), NOW(), 0),
(4309, 4005, 3008, NOW(), NOW(), 0),
(4310, 4005, 3006, NOW(), NOW(), 0),
(4311, 4006, 3002, NOW(), NOW(), 0),
(4312, 4006, 3010, NOW(), NOW(), 0);

-- ===================== P4：竞赛种子 =====================
--
-- 固定 id 段位（避免与雪花 id 冲突，便于联调直接引用）：
--   contest              5001–5099
--   contest_problem      5101–5199
--   contest_registration 5201–5299
--
-- 时间均以 **NOW() 为基准**写入，因此「进行中/已结束」只相对**灌数据那一刻**成立。
--    若要在很久以后重新演示，重放本文件即可（INSERT IGNORE 不会重复插入，
--    需要刷新时间窗口时先 DELETE 这三张表或改用手动建赛接口）。
--
-- 两场竞赛的用途分工（不要改成同一形态）：
--   5001 已结束 → 演示终榜、终榜快照留档、解封后公开榜等于完整榜；
--   5002 进行中且**未封榜** → 演示实时榜与 WebSocket 名次推送。
--        封榜演示请用 POST /contests/{id}/freeze 或建一场 freeze-minutes 很小的短赛程竞赛
--        （见 scripts/verify-p4.py 的自动流程，避免依赖人工等待）。
USE `judge_contest`;

INSERT IGNORE INTO `contest`
(`id`, `title`, `description`, `rule`, `start_time`, `end_time`, `freeze_minutes`, `freeze_at`,
 `penalty_minutes`, `status`, `owner_id`, `create_time`, `update_time`, `deleted`) VALUES
(5001, '【演示】算法热身赛（已结束·含终榜）',
 '3 小时赛程，赛前 90 分钟封榜。用于演示终榜、快照留档与「解封后公开榜即完整榜」。',
 'ACM', DATE_SUB(NOW(), INTERVAL 180 MINUTE), DATE_SUB(NOW(), INTERVAL 60 MINUTE),
 30, DATE_SUB(NOW(), INTERVAL 90 MINUTE), 20, 2, 2101, NOW(), NOW(), 0),
(5002, '【演示】周赛 #1（进行中）',
 '2 小时赛程，赛末 30 分钟封榜（尚未到封榜时刻）。用于演示 Redis 实时榜与 WebSocket 名次推送。',
 'ACM', DATE_SUB(NOW(), INTERVAL 30 MINUTE), DATE_ADD(NOW(), INTERVAL 120 MINUTE),
 30, DATE_ADD(NOW(), INTERVAL 90 MINUTE), 20, 1, 2102, NOW(), NOW(), 0);

-- 竞赛题目编排（题号 A/B/C 是本竞赛内的展示顺序，与题库 id 无关）
INSERT IGNORE INTO `contest_problem`
(`id`, `contest_id`, `problem_id`, `label`, `display_order`, `full_score`,
 `submit_count`, `accepted_count`, `create_time`, `update_time`, `deleted`) VALUES
-- 5001（已结束）：A+B / 求和 / 数组边界
(5101, 5001, 4001, 'A', 0, 100, 0, 0, NOW(), NOW(), 0),
(5102, 5001, 4002, 'B', 1, 100, 0, 0, NOW(), NOW(), 0),
(5103, 5001, 4004, 'C', 2, 100, 0, 0, NOW(), NOW(), 0),
-- 5002（进行中）：A+B / 求和 / 大数组求和（内存受限）
(5111, 5002, 4001, 'A', 0, 100, 0, 0, NOW(), NOW(), 0),
(5112, 5002, 4002, 'B', 1, 100, 0, 0, NOW(), NOW(), 0),
(5113, 5002, 4003, 'C', 2, 100, 0, 0, NOW(), NOW(), 0);

-- 报名数据：演示学员一~五报名 5002（进行中，可直接提交刷榜）；
--           演示学员一~三报名 5001（已结束，仅用于榜单展示）
INSERT IGNORE INTO `contest_registration`
(`id`, `contest_id`, `user_id`, `register_time`, `status`, `create_time`, `update_time`, `deleted`) VALUES
(5201, 5002, 2001, DATE_SUB(NOW(), INTERVAL 120 MINUTE), 1, NOW(), NOW(), 0),
(5202, 5002, 2002, DATE_SUB(NOW(), INTERVAL 118 MINUTE), 1, NOW(), NOW(), 0),
(5203, 5002, 2003, DATE_SUB(NOW(), INTERVAL 115 MINUTE), 1, NOW(), NOW(), 0),
(5204, 5002, 2004, DATE_SUB(NOW(), INTERVAL 110 MINUTE), 1, NOW(), NOW(), 0),
(5205, 5002, 2005, DATE_SUB(NOW(), INTERVAL 100 MINUTE), 1, NOW(), NOW(), 0),
(5211, 5001, 2001, DATE_SUB(NOW(), INTERVAL 200 MINUTE), 1, NOW(), NOW(), 0),
(5212, 5001, 2002, DATE_SUB(NOW(), INTERVAL 199 MINUTE), 1, NOW(), NOW(), 0),
(5213, 5001, 2003, DATE_SUB(NOW(), INTERVAL 198 MINUTE), 1, NOW(), NOW(), 0);

-- ===================== 2026-09-27 扩充：更多学员与题目 =====================
--
-- 固定 id 段位（延续上文约定）：
--   user / user_detail  学员 2006–2010
--   problem             4007–4012（全部已发布）
--   problem_version     4107–4112（一一对应，version_no = 1）
--   test_case           4218–4232（每题 2–3 个，各题分值合计 = 100）
--   problem_tag         4313–4324
--
-- 提交/榜单/竞赛数据**不在本文件**：提交必须走真实判题（结论由判题机产出，
-- 直接 INSERT verdict 会与逐用例结果、重交幂等、Redis 榜单全面脱节），
-- 统一由 scripts/reset-demo-data.py 的 seed/rank 阶段产生。

USE `judge_user`;

-- 学员（type=2）：扩充榜单与提交记录的参与面
INSERT IGNORE INTO `user` (`id`, `cell_phone`, `username`, `password`, `name`, `type`, `status`, `create_time`, `update_time`, `deleted`) VALUES
(2006, '13900000006', 'student006', '$2a$10$OuwRvnFKhKxYDdlndTfjXOzhWRnUF6jTJ6xZEnFlQHkAwcud6rELG', '演示学员六', 2, 1, NOW(), NOW(), 0),
(2007, '13900000007', 'student007', '$2a$10$OuwRvnFKhKxYDdlndTfjXOzhWRnUF6jTJ6xZEnFlQHkAwcud6rELG', '演示学员七', 2, 1, NOW(), NOW(), 0),
(2008, '13900000008', 'student008', '$2a$10$OuwRvnFKhKxYDdlndTfjXOzhWRnUF6jTJ6xZEnFlQHkAwcud6rELG', '演示学员八', 2, 1, NOW(), NOW(), 0),
(2009, '13900000009', 'student009', '$2a$10$OuwRvnFKhKxYDdlndTfjXOzhWRnUF6jTJ6xZEnFlQHkAwcud6rELG', '演示学员九', 2, 1, NOW(), NOW(), 0),
(2010, '13900000010', 'student010', '$2a$10$OuwRvnFKhKxYDdlndTfjXOzhWRnUF6jTJ6xZEnFlQHkAwcud6rELG', '演示学员十', 2, 1, NOW(), NOW(), 0);

USE `judge_problem`;

INSERT IGNORE INTO `problem`
(`id`, `title`, `difficulty`, `time_limit_ms`, `memory_limit_mb`, `status`, `owner_id`, `current_version_id`, `submit_count`, `accepted_count`, `create_time`, `update_time`, `deleted`) VALUES
(4007, '回文判断', 1, 1000, 256, 1, 2101, 4107, 0, 0, NOW(), NOW(), 0),
(4008, '二分查找（首次出现下标）', 2, 1000, 256, 1, 2101, 4108, 0, 0, NOW(), NOW(), 0),
(4009, '爬楼梯', 1, 1000, 256, 1, 2102, 4109, 0, 0, NOW(), NOW(), 0),
(4010, '找零钱（最少张数）', 2, 1000, 256, 1, 2102, 4110, 0, 0, NOW(), NOW(), 0),
(4011, '两数之和', 2, 1000, 256, 1, 2101, 4111, 0, 0, NOW(), NOW(), 0),
(4012, '矩阵顺时针旋转 90°', 3, 1000, 256, 1, 2102, 4112, 0, 0, NOW(), NOW(), 0);

INSERT IGNORE INTO `problem_version`
(`id`, `problem_id`, `version_no`, `statement`, `input_spec`, `output_spec`, `hint`, `template_code`, `created_by`, `create_time`, `update_time`, `deleted`) VALUES
(4107, 4007, 1,
 '给定一个仅含小写字母的字符串 s，判断它是否为回文串（正读与反读完全相同）。',
 '一行，一个字符串 s（1 ≤ |s| ≤ 1000）。',
 '一行：是回文输出 YES，否则输出 NO。',
 '直接比较 s 与它的逆序即可，注意不要在字符串首尾引入多余空白。',
 '{"java":"import java.util.*;\\npublic class Main{\\n  public static void main(String[] args){\\n    Scanner sc = new Scanner(System.in);\\n    String s = sc.next().trim();\\n    StringBuilder r = new StringBuilder(s).reverse();\\n    System.out.println(s.contentEquals(r) ? \\"YES\\" : \\"NO\\");\\n  }\\n}","python":"import sys\\ns = sys.stdin.read().strip()\\nprint(\'YES\' if s == s[::-1] else \'NO\')","cpp":"#include <bits/stdc++.h>\\nusing namespace std;\\nint main(){ string s; cin >> s; string r(s.rbegin(), s.rend()); cout << (s == r ? \\"YES\\" : \\"NO\\") << endl; return 0; }"}',
 2101, NOW(), NOW(), 0),

(4108, 4008, 1,
 '给定一个严格按升序排列的整数数组与 q 次询问。对每个询问的整数 x：若在数组中存在，输出它**首次出现**的下标（从 0 开始）；否则输出 -1。',
 '第一行两个整数 n、q（1 ≤ n ≤ 10^5，1 ≤ q ≤ 10^5）；第二行 n 个升序整数；随后 q 行每行一个整数 x。',
 '共 q 行，每行一个整数：x 首次出现的下标或 -1。',
 '数组有序，直接用二分求「第一个大于等于 x 的位置」，再校验该位置是否恰为 x。',
 '{"java":"import java.io.*;\\npublic class Main{\\n  public static void main(String[] args) throws Exception{\\n    StreamTokenizer in = new StreamTokenizer(new BufferedInputStream(System.in));\\n    in.nextToken(); int n = (int) in.nval; in.nextToken(); int q = (int) in.nval;\\n    long[] a = new long[n];\\n    for (int i = 0; i < n; i++){ in.nextToken(); a[i] = (long) in.nval; }\\n    StringBuilder sb = new StringBuilder();\\n    while (q-- > 0){ in.nextToken(); long x = (long) in.nval;\\n      int lo = 0, hi = n;\\n      while (lo < hi){ int m = (lo + hi) >>> 1; if (a[m] < x) lo = m + 1; else hi = m; }\\n      sb.append(lo < n && a[lo] == x ? String.valueOf(lo) : \\"-1\\").append(\'\\n\');\\n    }\\n    System.out.print(sb);\\n  }\\n}","python":"import sys\\nd = sys.stdin.read().split()\\nn, q = int(d[0]), int(d[1])\\na = list(map(int, d[2:2+n]))\\nout = []\\nfor i in range(q):\\n    x = int(d[2+n+i])\\n    lo, hi = 0, n\\n    while lo < hi:\\n        m = (lo + hi) // 2\\n        if a[m] < x: lo = m + 1\\n        else: hi = m\\n    out.append(str(lo) if lo < n and a[lo] == x else \'-1\')\\nprint(\'\\\\n\'.join(out))"}',
 2101, NOW(), NOW(), 0),

(4109, 4009, 1,
 '你正在爬一段 n 阶的楼梯，每次可以上 1 阶或 2 阶。问：到达顶端共有多少种不同的走法？f(1)=1，f(2)=2。',
 '一行，一个整数 n（1 ≤ n ≤ 60）。',
 '一行，一个整数，表示走法总数。',
 '这是斐波那契数列的变体：f(n) = f(n-1) + f(n-2)。n=45 时结果约 1.8×10^9，请使用 64 位整型。',
 '{"java":"import java.util.*;\\npublic class Main{\\n  public static void main(String[] args){\\n    long n = new Scanner(System.in).nextLong();\\n    long a = 1, b = 2;\\n    for (long i = 1; i < n; i++){ long t = a + b; a = b; b = t; }\\n    System.out.println(a);\\n  }\\n}","python":"n = int(input())\\na, b = 1, 2\\nfor _ in range(n - 1):\\n    a, b = b, a + b\\nprint(a)","cpp":"#include <iostream>\\nusing namespace std;\\nint main(){ long long n; cin >> n; long long a = 1, b = 2;\\n  for (long long i = 1; i < n; i++){ long long t = a + b; a = b; b = t; }\\n  cout << a << endl; return 0; }"}',
 2102, NOW(), NOW(), 0),

(4110, 4010, 1,
 '人民币面额为 1、5、10、20、50、100。给定金额 m，求凑出 m 所需的最少纸币张数。保证 m 可以被凑出。',
 '一行，一个整数 m（1 ≤ m ≤ 10^9）。',
 '一行，一个整数，表示最少张数。',
 '贪心：每次优先使用不大于剩余金额的最大面额。本题面额体系下贪心即最优。',
 '{"java":"import java.util.*;\\npublic class Main{\\n  public static void main(String[] args){\\n    long m = new Scanner(System.in).nextLong();\\n    int c = 0;\\n    long[] d = {100, 50, 20, 10, 5, 1};\\n    for (long v : d){ c += m / v; m %= v; }\\n    System.out.println(c);\\n  }\\n}","python":"m = int(input())\\nc = 0\\nfor v in (100, 50, 20, 10, 5, 1):\\n    c += m // v\\n    m %= v\\nprint(c)","cpp":"#include <iostream>\\nusing namespace std;\\nint main(){ long long m; cin >> m; int c = 0;\\n  long long d[] = {100, 50, 20, 10, 5, 1};\\n  for (long long v : d){ c += m / v; m %= v; }\\n  cout << c << endl; return 0; }"}',
 2102, NOW(), NOW(), 0),

(4111, 4011, 1,
 '给定 n 个整数与目标值 target，求数组中和恰为 target 的两个整数的小下标在前、大下标在后的组合（下标从 0 开始），保证恰有一组解。',
 '第一行两个整数 n、target（2 ≤ n ≤ 10^4）；第二行 n 个整数。',
 '一行，两个整数 i j（i < j），用空格分隔。',
 '用哈希表记录「值 → 首次出现的下标」，遍历时查询 target - 当前值是否已出现过。',
 '{"java":"import java.util.*;\\npublic class Main{\\n  public static void main(String[] args){\\n    Scanner sc = new Scanner(System.in);\\n    int n = sc.nextInt(), t = sc.nextInt();\\n    int[] a = new int[n];\\n    for (int i = 0; i < n; i++) a[i] = sc.nextInt();\\n    Map<Integer, Integer> pos = new HashMap<>();\\n    for (int i = 0; i < n; i++){\\n      if (pos.containsKey(t - a[i])){ System.out.println(pos.get(t - a[i]) + \\" \\" + i); return; }\\n      pos.put(a[i], i);\\n    }\\n  }\\n}","python":"import sys\\nd = sys.stdin.read().split()\\nn, t = int(d[0]), int(d[1])\\na = list(map(int, d[2:2+n]))\\npos = {}\\nfor i, v in enumerate(a):\\n    if t - v in pos:\\n        print(pos[t - v], i)\\n        break\\n    pos[v] = i","cpp":"#include <bits/stdc++.h>\\nusing namespace std;\\nint main(){ int n, t; cin >> n >> t;\\n  vector<long long> a(n); for (auto &v : a) cin >> v;\\n  unordered_map<long long, int> pos;\\n  for (int i = 0; i < n; i++){\\n    auto it = pos.find(t - a[i]);\\n    if (it != pos.end()){ cout << it->second << \\" \\" << i << endl; return 0; }\\n    pos[a[i]] = i;\\n  }\\n  return 0; }"}',
 2101, NOW(), NOW(), 0),

(4112, 4012, 1,
 '给定一个 n×n 的整数矩阵，将它顺时针旋转 90° 后输出。即 new[i][j] = old[n-1-j][i]。',
 '第一行一个整数 n（1 ≤ n ≤ 100）；随后 n 行每行 n 个整数。',
 'n 行，每行 n 个整数，以空格分隔，表示旋转后的矩阵。',
 '先按主对角线转置，再每行左右翻转，等价于顺时针旋转 90°。',
 '{"java":"import java.util.*;\\npublic class Main{\\n  public static void main(String[] args){\\n    Scanner sc = new Scanner(System.in);\\n    int n = sc.nextInt();\\n    String[][] a = new String[n][n];\\n    for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) a[i][j] = sc.next();\\n    StringBuilder sb = new StringBuilder();\\n    for (int i = 0; i < n; i++){\\n      for (int j = 0; j < n; j++){ if (j > 0) sb.append(\' \'); sb.append(a[n-1-j][i]); }\\n      sb.append(\'\\n\');\\n    }\\n    System.out.print(sb);\\n  }\\n}","python":"import sys\\nd = sys.stdin.read().split()\\nn = int(d[0])\\na = [d[1+i*n:1+(i+1)*n] for i in range(n)]\\nfor i in range(n):\\n    print(\' \'.join(a[n-1-j][i] for j in range(n)))","cpp":"#include <bits/stdc++.h>\\nusing namespace std;\\nint main(){ int n; cin >> n;\\n  vector<vector<string>> a(n, vector<string>(n));\\n  for (auto &r : a) for (auto &v : r) cin >> v;\\n  for (int i = 0; i < n; i++){\\n    for (int j = 0; j < n; j++){ if (j) cout << \' \'; cout << a[n-1-j][i]; }\\n    cout << endl;\\n  }\\n  return 0; }"}',
 2102, NOW(), NOW(), 0);

-- 测试用例（各题分值合计 = 100；is_hidden=1 仅归属教师/管理员可见）
INSERT IGNORE INTO `test_case`
(`id`, `problem_id`, `seq`, `stdin`, `expected_stdout`, `is_hidden`, `score`, `time_limit_ms`, `judge_mode`, `create_time`, `update_time`, `deleted`) VALUES
-- 4007 回文判断
(4218, 4007, 1, 'aba', 'YES', 0, 30, NULL, 0, NOW(), NOW(), 0),
(4219, 4007, 2, 'abca', 'NO', 0, 30, NULL, 0, NOW(), NOW(), 0),
(4220, 4007, 3, 'abcba', 'YES', 1, 40, NULL, 0, NOW(), NOW(), 0),
-- 4008 二分查找
(4221, 4008, 1, '5 2\n1 3 5 7 9\n5\n9', '2\n4', 0, 30, NULL, 0, NOW(), NOW(), 0),
(4222, 4008, 2, '3 1\n2 2 2\n2', '0', 0, 30, NULL, 0, NOW(), NOW(), 0),
(4223, 4008, 3, '4 1\n1 2 4 8\n3', '-1', 1, 40, NULL, 0, NOW(), NOW(), 0),
-- 4009 爬楼梯（f(45) = 斐波那契 1836311903）
(4224, 4009, 1, '3', '3', 0, 40, NULL, 0, NOW(), NOW(), 0),
(4225, 4009, 2, '45', '1836311903', 1, 60, NULL, 0, NOW(), NOW(), 0),
-- 4010 找零钱（999 = 100×9 + 50 + 20×2 + 5 + 1×4 = 17 张）
(4226, 4010, 1, '73', '5', 0, 30, NULL, 0, NOW(), NOW(), 0),
(4227, 4010, 2, '100', '1', 0, 30, NULL, 0, NOW(), NOW(), 0),
(4228, 4010, 3, '999', '17', 1, 40, NULL, 0, NOW(), NOW(), 0),
-- 4011 两数之和
(4229, 4011, 1, '4 9\n2 7 11 15', '0 1', 0, 50, NULL, 0, NOW(), NOW(), 0),
(4230, 4011, 2, '3 6\n3 2 4', '1 2', 1, 50, NULL, 0, NOW(), NOW(), 0),
-- 4012 矩阵旋转
(4231, 4012, 1, '2\n1 2\n3 4', '3 1\n4 2', 0, 40, NULL, 0, NOW(), NOW(), 0),
(4232, 4012, 2, '3\n1 2 3\n4 5 6\n7 8 9', '7 4 1\n8 5 2\n9 6 3', 1, 60, NULL, 0, NOW(), NOW(), 0);

-- 题目-标签关联（标签 3001–3010 见上文）
INSERT IGNORE INTO `problem_tag`
(`id`, `problem_id`, `tag_id`, `create_time`, `update_time`, `deleted`) VALUES
(4313, 4007, 3002, NOW(), NOW(), 0),
(4314, 4007, 3010, NOW(), NOW(), 0),
(4315, 4008, 3005, NOW(), NOW(), 0),
(4316, 4008, 3001, NOW(), NOW(), 0),
(4317, 4009, 3006, NOW(), NOW(), 0),
(4318, 4009, 3009, NOW(), NOW(), 0),
(4319, 4010, 3007, NOW(), NOW(), 0),
(4320, 4010, 3010, NOW(), NOW(), 0),
(4321, 4011, 3003, NOW(), NOW(), 0),
(4322, 4011, 3001, NOW(), NOW(), 0),
(4323, 4012, 3010, NOW(), NOW(), 0),
(4324, 4012, 3001, NOW(), NOW(), 0);
