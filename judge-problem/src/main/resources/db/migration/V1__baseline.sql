-- ============================================================
-- V1__baseline.sql —— 由 sql/init.sql 自动拆分生成（logs/tmp/c1_split_flyway.py）
-- 语义：与 init.sql 中本服务的建表段完全一致（CREATE TABLE IF NOT EXISTS）。
-- 存量数据卷：spring.flyway.baseline-on-migrate=true 会在首次启动时打 baseline
--   （baseline-version=1），不会重放本文件；全新数据卷则由本文件建表。
-- ⚠️ 后续 schema 变更：新增 V2__xxx.sql 递进，不要改写本文件。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `problem` (
    `id` BIGINT NOT NULL,
    `title` VARCHAR(255) NOT NULL COMMENT '题目标题',
    `difficulty` TINYINT DEFAULT 1 COMMENT '难度:1~5',
    `time_limit_ms` INT DEFAULT 1000 COMMENT '时间限制(ms)',
    `memory_limit_mb` INT DEFAULT 256 COMMENT '内存限制(MB)',
    `status` TINYINT DEFAULT 0 COMMENT '状态:0草稿/1已发布/2已下线',
    `owner_id` BIGINT COMMENT '创建教师id（归属校验用）',
    `current_version_id` BIGINT COMMENT '当前题面版本id',
    `submit_count` INT DEFAULT 0 COMMENT '提交次数',
    `accepted_count` INT DEFAULT 0 COMMENT '通过次数',
    `create_time` DATETIME,
    `update_time` DATETIME,
    `creater` BIGINT,
    `updater` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_problem_status_difficulty` (`status`, `difficulty`),
    KEY `idx_problem_owner` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='题目表';

CREATE TABLE IF NOT EXISTS `problem_version` (
    `id` BIGINT NOT NULL,
    `problem_id` BIGINT NOT NULL COMMENT '题目id',
    `version_no` INT NOT NULL COMMENT '版本号，从 1 递增',
    `statement` TEXT COMMENT '题面(Markdown)',
    `input_spec` TEXT COMMENT '输入说明',
    `output_spec` TEXT COMMENT '输出说明',
    `hint` TEXT COMMENT '提示/样例说明',
    `template_code` JSON COMMENT '各语言模板代码 {"java":"...","python":"..."}',
    `created_by` BIGINT COMMENT '修改人',
    `create_time` DATETIME,
    `update_time` DATETIME,
    `creater` BIGINT,
    `updater` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_problem_version` (`problem_id`, `version_no`),
    KEY `idx_problem_version_problem` (`problem_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='题面版本表（改题即新增版本，保证历史提交可复现）';

CREATE TABLE IF NOT EXISTS `test_case` (
    `id` BIGINT NOT NULL,
    `problem_id` BIGINT NOT NULL COMMENT '题目id',
    `seq` INT NOT NULL COMMENT '执行顺序，从 1 开始',
    `stdin` TEXT COMMENT '标准输入',
    `expected_stdout` TEXT COMMENT '期望输出',
    `is_hidden` TINYINT DEFAULT 0 COMMENT '是否隐藏:0可见(样例)/1隐藏',
    `score` INT DEFAULT 0 COMMENT '该用例分值',
    `time_limit_ms` INT COMMENT '用例级时间限制覆盖（NULL 用题目限制）',
    `judge_mode` TINYINT DEFAULT 0 COMMENT '比对模式:0精确/1浮点容差/2特判',
    `create_time` DATETIME,
    `update_time` DATETIME,
    `creater` BIGINT,
    `updater` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_test_case_seq` (`problem_id`, `seq`),
    KEY `idx_test_case_problem_hidden` (`problem_id`, `is_hidden`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='测试用例表';

CREATE TABLE IF NOT EXISTS `tag` (
    `id` BIGINT NOT NULL,
    `name` VARCHAR(64) NOT NULL COMMENT '标签名',
    `type` VARCHAR(32) COMMENT '标签类型：ALGORITHM/SOURCE/DIFFICULTY_TAG',
    `create_time` DATETIME,
    `update_time` DATETIME,
    `creater` BIGINT,
    `updater` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tag_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标签表';

CREATE TABLE IF NOT EXISTS `problem_tag` (
    `id` BIGINT NOT NULL,
    `problem_id` BIGINT NOT NULL,
    `tag_id` BIGINT NOT NULL,
    `create_time` DATETIME,
    `update_time` DATETIME,
    `creater` BIGINT,
    `updater` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_problem_tag` (`problem_id`, `tag_id`),
    KEY `idx_problem_tag_tag` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='题目-标签关联表';

-- ============================================================
-- 四、提交服务库 judge_submission
-- ============================================================
