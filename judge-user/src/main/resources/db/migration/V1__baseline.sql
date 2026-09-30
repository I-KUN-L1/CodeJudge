-- ============================================================
-- V1__baseline.sql —— 由 sql/init.sql 自动拆分生成（logs/tmp/c1_split_flyway.py）
-- 语义：与 init.sql 中本服务的建表段完全一致（CREATE TABLE IF NOT EXISTS）。
-- 存量数据卷：spring.flyway.baseline-on-migrate=true 会在首次启动时打 baseline
--   （baseline-version=1），不会重放本文件；全新数据卷则由本文件建表。
-- ⚠️ 后续 schema 变更：新增 V2__xxx.sql 递进，不要改写本文件。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT NOT NULL,
    `cell_phone` VARCHAR(20) COMMENT '手机号',
    `username` VARCHAR(64) COMMENT '用户名',
    `password` VARCHAR(128) COMMENT '密码(BCrypt)',
    `name` VARCHAR(64) COMMENT '姓名',
    `type` INT DEFAULT 2 COMMENT '类型:1员工(管理员)/2学员/3教师',
    `status` INT DEFAULT 1 COMMENT '状态:0禁用/1正常',
    `icon` VARCHAR(255) COMMENT '头像',
    `email` VARCHAR(128) COMMENT '邮箱',
    `city` VARCHAR(64) COMMENT '城市',
    `gender` INT COMMENT '性别',
    `create_time` DATETIME,
    `update_time` DATETIME,
    `creater` BIGINT,
    `updater` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_cell_phone` (`cell_phone`),
    KEY `idx_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

CREATE TABLE IF NOT EXISTS `user_detail` (
    `id` BIGINT NOT NULL,
    `user_id` BIGINT COMMENT '用户id',
    `job_title` VARCHAR(64) COMMENT '教师职称',
    `intro` VARCHAR(1000) COMMENT '简介',
    `school` VARCHAR(128) COMMENT '学校',
    `signature` VARCHAR(255) COMMENT '个性签名',
    `birthday` DATE COMMENT '生日',
    `education` VARCHAR(32) COMMENT '学历',
    `occupation` VARCHAR(64) COMMENT '职业',
    `create_time` DATETIME,
    `update_time` DATETIME,
    `creater` BIGINT,
    `updater` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_user_detail_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户详情表';

-- 首个管理员不在脚本中硬编码凭据：由 judge-auth 启动时的安全引导生成
-- （检测到无管理员时创建，BCrypt 加密入库，凭据写入 .bootstrap-credentials，首次改密后自动删除）
-- 初始口令来源：CJ_ADMIN_INIT_PASSWORD；**未配置时生成一次性随机强口令**，
-- 因此源码与 SQL 里都不存在任何可预测的默认管理员口令。

-- ============================================================
-- 三、题目服务库 judge_problem
-- ============================================================
