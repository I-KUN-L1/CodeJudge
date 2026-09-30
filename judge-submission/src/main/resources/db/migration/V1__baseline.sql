-- ============================================================
-- V1__baseline.sql —— 由 sql/init.sql 自动拆分生成（logs/tmp/c1_split_flyway.py）
-- 语义：与 init.sql 中本服务的建表段完全一致（CREATE TABLE IF NOT EXISTS）。
-- 存量数据卷：spring.flyway.baseline-on-migrate=true 会在首次启动时打 baseline
--   （baseline-version=1），不会重放本文件；全新数据卷则由本文件建表。
-- ⚠️ 后续 schema 变更：新增 V2__xxx.sql 递进，不要改写本文件。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `submission` (
    `id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL COMMENT '提交人',
    `problem_id` BIGINT NOT NULL COMMENT '题目id',
    `contest_id` BIGINT DEFAULT 0 COMMENT '竞赛id，0=非竞赛提交',
    `language` VARCHAR(16) NOT NULL COMMENT '语言:JAVA/PYTHON/CPP/GO',
    `code` TEXT COMMENT '代码全文（≤32KB 存此列）',
    `code_path` VARCHAR(255) COMMENT '超长代码转对象存储(MinIO)的相对路径',
    `code_hash` VARCHAR(64) COMMENT '代码 SHA-256，参与提交幂等',
    `submit_round` INT DEFAULT 0 COMMENT '同题同码重复提交轮次，参与提交幂等',
    `status` VARCHAR(16) DEFAULT 'PENDING' COMMENT '状态:PENDING/JUDGING/SUCCESS/FAILED',
    `verdict` VARCHAR(8) COMMENT '判题结论:AC/WA/TLE/MLE/RE/CE/SE',
    `score` INT DEFAULT 0 COMMENT '得分',
    `time_ms` INT COMMENT '最大耗时(ms)',
    `memory_kb` INT COMMENT '最大内存(KB)',
    `compile_info_id` BIGINT COMMENT '编译信息id',
    `submit_time` DATETIME COMMENT '提交时间',
    `create_time` DATETIME,
    `update_time` DATETIME,
    `creater` BIGINT,
    `updater` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    -- 幂等的数据库侧基石：同题同码重复提交会被拦在这一层（Redis 幂等 key 是快路径，
    -- 唯一索引是最终防线；两者缺一都会在并发下漏判）
    UNIQUE KEY `uk_submission_idempotent` (`user_id`, `problem_id`, `contest_id`, `code_hash`, `submit_round`),
    KEY `idx_submission_user_time` (`user_id`, `submit_time`),
    KEY `idx_submission_problem` (`problem_id`),
    KEY `idx_submission_contest_user` (`contest_id`, `user_id`),
    KEY `idx_submission_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提交记录表';

CREATE TABLE IF NOT EXISTS `judge_task` (
    `id` BIGINT NOT NULL,
    `submission_id` BIGINT NOT NULL COMMENT '提交id',
    `worker_id` VARCHAR(128) COMMENT '处理中的判题机id',
    `status` VARCHAR(16) DEFAULT 'PENDING' COMMENT '状态:PENDING/JUDGING/SUCCESS/FAILED/DEAD',
    `attempt` INT DEFAULT 0 COMMENT '已重试次数',
    `max_attempt` INT DEFAULT 3 COMMENT '最大重试次数',
    `timeout_ms` INT DEFAULT 60000 COMMENT '任务级超时（含编译）',
    `lease_owner` VARCHAR(128) COMMENT '租约持有者 workerId',
    `lease_expire_at` DATETIME COMMENT '租约到期时间（到期即可被其他 worker 接管 → 故障转移）',
    `next_retry_at` DATETIME COMMENT '下次可重试时间（延迟消息兜底）',
    `error_msg` VARCHAR(512) COMMENT '最近一次失败原因',
    `create_time` DATETIME,
    `update_time` DATETIME,
    `creater` BIGINT,
    `updater` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_judge_task_submission` (`submission_id`),
    -- 故障转移扫描：worker 宕机后由补偿任务按 (status, lease_expire_at) 捞出待接管任务
    KEY `idx_judge_task_status_lease` (`status`, `lease_expire_at`),
    KEY `idx_judge_task_retry` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='判题任务表（含租约，支撑超时重试与故障转移）';

CREATE TABLE IF NOT EXISTS `judge_result` (
    `id` BIGINT NOT NULL,
    `submission_id` BIGINT NOT NULL,
    `task_id` BIGINT COMMENT '判题任务id',
    `case_id` BIGINT COMMENT '用例id',
    `seq` INT COMMENT '用例执行序号',
    `verdict` VARCHAR(8) COMMENT '该用例结论',
    `time_ms` INT COMMENT '该用例耗时',
    `memory_kb` INT COMMENT '该用例内存峰值',
    `output_digest` VARCHAR(512) COMMENT '实际输出摘要（截断，隐藏用例不回传全文）',
    `stderr_digest` VARCHAR(512) COMMENT '错误输出摘要',
    `create_time` DATETIME,
    `update_time` DATETIME,
    `creater` BIGINT,
    `updater` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_judge_result_submission` (`submission_id`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='逐用例判题结果表';

CREATE TABLE IF NOT EXISTS `compile_info` (
    `id` BIGINT NOT NULL,
    `submission_id` BIGINT NOT NULL,
    `success` TINYINT DEFAULT 0 COMMENT '编译是否成功:0否/1是',
    `stdout_log` TEXT COMMENT '编译标准输出（截断保护）',
    `stderr_log` TEXT COMMENT '编译错误输出（CE 时用于前端展示与 AI 诊断）',
    `duration_ms` INT COMMENT '编译耗时',
    `create_time` DATETIME,
    `update_time` DATETIME,
    `creater` BIGINT,
    `updater` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_compile_info_submission` (`submission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='编译信息表';

-- ============================================================
-- 五、竞赛服务库 judge_contest
-- ============================================================
