-- ============================================================
-- 2026-10-07 迁移：代码草稿表 code_draft（judge_submission 库）
--
-- 背景：演示账户需要"所有题目的标准答案已填写完毕但尚未提交"——
-- 即编辑器内容的持久化，而非真实提交记录。预插入 submission 行不可行：
-- 它会进入判题状态机并被 uk_submission_idempotent 幂等去重挡住，
-- 演示时点"提交"反而会被判为重复提交。
--
-- 已有数据卷请手工执行本文件（MySQL 官方镜像的 initdb 脚本只在空卷时执行）：
--   docker exec -i codejudge-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" < sql/migrations/2026-10-07-code-draft.sql
-- 新空卷无需执行：sql/init.sql 已包含同名 DDL。
-- ============================================================
SET NAMES utf8mb4;

USE `judge_submission`;

CREATE TABLE IF NOT EXISTS `code_draft` (
    `id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL COMMENT '归属用户（草稿只对本人可见）',
    `problem_id` BIGINT NOT NULL COMMENT '题目id',
    `language` VARCHAR(16) NOT NULL DEFAULT 'JAVA' COMMENT '语言:JAVA/PYTHON/CPP/GO',
    `code` TEXT COMMENT '代码全文（≤32KB，与 submission.code 同限）',
    `create_time` DATETIME,
    `update_time` DATETIME,
    `creater` BIGINT,
    `updater` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_draft_user_problem` (`user_id`, `problem_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代码草稿表（答案已填写但尚未提交，一题一份；非提交记录，不参与判题与幂等）';
