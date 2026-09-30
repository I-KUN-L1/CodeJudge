-- ============================================================
-- V1__baseline.sql —— 由 sql/init.sql 自动拆分生成（logs/tmp/c1_split_flyway.py）
-- 语义：与 init.sql 中本服务的建表段完全一致（CREATE TABLE IF NOT EXISTS）。
-- 存量数据卷：spring.flyway.baseline-on-migrate=true 会在首次启动时打 baseline
--   （baseline-version=1），不会重放本文件；全新数据卷则由本文件建表。
-- ⚠️ 后续 schema 变更：新增 V2__xxx.sql 递进，不要改写本文件。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `role` (
    `id` BIGINT NOT NULL COMMENT '主键',
    `name` VARCHAR(64) NOT NULL COMMENT '角色名称',
    `code` VARCHAR(64) COMMENT '角色编码',
    `remark` VARCHAR(255) COMMENT '备注',
    `create_time` DATETIME COMMENT '创建时间',
    `update_time` DATETIME COMMENT '更新时间',
    `creater` BIGINT COMMENT '创建人',
    `updater` BIGINT COMMENT '更新人',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

CREATE TABLE IF NOT EXISTS `menu` (
    `id` BIGINT NOT NULL,
    `parent_id` BIGINT DEFAULT 0 COMMENT '父菜单id',
    `name` VARCHAR(64) NOT NULL COMMENT '菜单名称',
    `path` VARCHAR(255) COMMENT '路由地址',
    `component` VARCHAR(255) COMMENT '组件路径',
    `icon` VARCHAR(255) COMMENT '图标',
    `sort` INT DEFAULT 0 COMMENT '排序',
    `type` INT DEFAULT 1 COMMENT '类型',
    `status` INT DEFAULT 1 COMMENT '状态',
    `create_time` DATETIME,
    `update_time` DATETIME,
    `creater` BIGINT,
    `updater` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单表';

CREATE TABLE IF NOT EXISTS `privilege` (
    `id` BIGINT NOT NULL,
    `menu_id` BIGINT COMMENT '菜单id',
    `method` VARCHAR(16) COMMENT '请求方法',
    `uri` VARCHAR(255) COMMENT '请求路径',
    `name` VARCHAR(64) COMMENT '权限名称',
    `description` VARCHAR(255) COMMENT '描述',
    `create_time` DATETIME,
    `update_time` DATETIME,
    `creater` BIGINT,
    `updater` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限表';

CREATE TABLE IF NOT EXISTS `account_role` (
    `id` BIGINT NOT NULL,
    `account_id` BIGINT NOT NULL COMMENT '账号id',
    `role_id` BIGINT NOT NULL COMMENT '角色id',
    `create_time` DATETIME,
    `update_time` DATETIME,
    `creater` BIGINT,
    `updater` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_account_role` (`account_id`, `role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号角色关联表';

CREATE TABLE IF NOT EXISTS `role_menu` (
    `id` BIGINT NOT NULL,
    `role_id` BIGINT NOT NULL,
    `menu_id` BIGINT NOT NULL,
    `create_time` DATETIME,
    `update_time` DATETIME,
    `creater` BIGINT,
    `updater` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_menu` (`role_id`, `menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色菜单关联表';

CREATE TABLE IF NOT EXISTS `role_privilege` (
    `id` BIGINT NOT NULL,
    `role_id` BIGINT NOT NULL,
    `privilege_id` BIGINT NOT NULL,
    `create_time` DATETIME,
    `update_time` DATETIME,
    `creater` BIGINT,
    `updater` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_privilege` (`role_id`, `privilege_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限关联表';

CREATE TABLE IF NOT EXISTS `login_record` (
    `id` BIGINT NOT NULL,
    `user_id` BIGINT COMMENT '用户id',
    `cell_phone` VARCHAR(20) COMMENT '手机号',
    `ipv4` VARCHAR(64) COMMENT '登录IP',
    `login_type` INT COMMENT '登录类型：0=用户端 1=管理端',
    `login_time` DATETIME COMMENT '登录时间',
    `create_time` DATETIME,
    `update_time` DATETIME,
    `creater` BIGINT,
    `updater` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_login_record_user` (`user_id`, `login_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录记录表';

-- ============================================================
-- 二、用户服务库 judge_user
-- 账号 + 密码（BCrypt）落在这里。JWT 的 role claim 取自 user.type，
-- 不依赖 account_role —— 因此登录链路只需本表即可完成鉴权。
-- ============================================================
