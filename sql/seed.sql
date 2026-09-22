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
-- ⚠ 必须首先执行：显式声明会话字符集（原因见 sql/init.sql 顶部同段注释）。
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
-- ⚠ 教师账号不开放自助注册（见 TeacherController 的安全加固说明），仅能由员工/管理员开通
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
-- ⚠️ template_code 是 JSON 列：换行必须写成 \\n（两个字符 \ 与 n），
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
(4209, 4003, 2, '10000000\n1 1 1 1 1 1 1 1 1 1', '10000000', 1, 45, NULL, 0, NOW(), NOW(), 0),
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
-- ⚠️ 时间均以 **NOW() 为基准**写入，因此「进行中/已结束」只相对**灌数据那一刻**成立。
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
