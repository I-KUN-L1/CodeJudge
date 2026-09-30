-- ============================================================
-- V1__baseline.sql —— 由 sql/init.sql 自动拆分生成（logs/tmp/c1_split_flyway.py）
-- 语义：与 init.sql 中本服务的建表段完全一致（CREATE TABLE IF NOT EXISTS）。
-- 存量数据卷：spring.flyway.baseline-on-migrate=true 会在首次启动时打 baseline
--   （baseline-version=1），不会重放本文件；全新数据卷则由本文件建表。
-- ⚠️ 后续 schema 变更：新增 V2__xxx.sql 递进，不要改写本文件。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `contest` (
    `id` BIGINT NOT NULL,
    `title` VARCHAR(255) NOT NULL,
    `description` TEXT COMMENT '竞赛说明',
    `rule` VARCHAR(16) DEFAULT 'ACM' COMMENT '赛制:ACM(罚时)/IOI(按分)',
    `start_time` DATETIME NOT NULL,
    `end_time` DATETIME NOT NULL,
    `freeze_minutes` INT DEFAULT 0 COMMENT '封榜时长(分钟)，0=不封榜',
    `freeze_at` DATETIME COMMENT '封榜时刻 = end_time - freeze_minutes',
    `penalty_minutes` INT DEFAULT 20 COMMENT 'ACM 每次错误提交罚时(分钟)',
    `status` TINYINT DEFAULT 0 COMMENT '状态:0未开始/1进行中/2已结束',
    `owner_id` BIGINT COMMENT '创建人（归属校验用）',
    `create_time` DATETIME,
    `update_time` DATETIME,
    `creater` BIGINT,
    `updater` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_contest_time` (`start_time`, `end_time`),
    KEY `idx_contest_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='竞赛表';

CREATE TABLE IF NOT EXISTS `contest_problem` (
    `id` BIGINT NOT NULL,
    `contest_id` BIGINT NOT NULL,
    `problem_id` BIGINT NOT NULL,
    `label` VARCHAR(8) COMMENT '题号展示:A/B/C…',
    `display_order` INT DEFAULT 0 COMMENT '展示顺序',
    `full_score` INT DEFAULT 100 COMMENT '满分（IOI 赛制使用）',
    `submit_count` INT DEFAULT 0,
    `accepted_count` INT DEFAULT 0,
    `create_time` DATETIME,
    `update_time` DATETIME,
    `creater` BIGINT,
    `updater` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_contest_problem` (`contest_id`, `problem_id`),
    KEY `idx_contest_problem_problem` (`problem_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='竞赛题目表';

CREATE TABLE IF NOT EXISTS `contest_registration` (
    `id` BIGINT NOT NULL,
    `contest_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `register_time` DATETIME COMMENT '报名时间',
    `status` TINYINT DEFAULT 1 COMMENT '状态:0取消/1已报名',
    `create_time` DATETIME,
    `update_time` DATETIME,
    `creater` BIGINT,
    `updater` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_contest_registration` (`contest_id`, `user_id`),
    KEY `idx_contest_registration_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='竞赛报名表';

CREATE TABLE IF NOT EXISTS `contest_rank_snapshot` (
    `id` BIGINT NOT NULL,
    `contest_id` BIGINT NOT NULL,
    `snapshot_type` VARCHAR(16) COMMENT '类型:FROZEN(封榜快照)/FINAL(终榜)',
    `snapshot_at` DATETIME COMMENT '快照时刻',
    `rank_json` LONGTEXT COMMENT '榜单 JSON（含排名、通过题数、罚时、各题状态）',
    `create_time` DATETIME,
    `update_time` DATETIME,
    `creater` BIGINT,
    `updater` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_rank_snapshot_contest_type` (`contest_id`, `snapshot_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='榜单快照表（封榜/终榜留档，Redis 掉数据后可据此重建）';
