-- ============================================================
-- CodeJudge 分布式在线编程评测平台 —— 数据库初始化脚本
--
-- 按服务分库（MySQL 8）：
--   judge_auth / judge_user / judge_problem / judge_submission / judge_contest
-- 另有 judge_ai（PostgreSQL + pgvector，见 deploy/pgvector/init.sql，
-- 因为向量检索依赖 pgvector 扩展，不适合放在 MySQL）。
--
-- 公共字段约定：id / create_time / update_time / creater / updater / deleted（逻辑删除）
-- 主键统一使用雪花 ID（BIGINT，无 AUTO_INCREMENT），与 judge-common 的 SnowflakeIdGenerator 一致。
--
-- 为什么一次性把后续阶段的表也建好？
--   MySQL 官方镜像的 /docker-entrypoint-initdb.d 脚本**只在数据卷为空时执行一次**。
--   若 P1 只建 auth/user，P2 再往本文件追加 problem 等 DDL，已初始化过的卷不会重新执行，
--   必须手工 docker exec 灌入或删除数据卷重来 —— 容易在联调时踩坑。
--   因此这里一次性给出全量 schema；后续新增列/索引请放 sql/migrations/ 下按日期命名。
--
-- 种子数据（账号、题目、竞赛）单独放在 sql/seed.sql，可幂等重复执行。
-- ============================================================

-- ------------------------------------------------------------------
-- 必须首先执行：显式声明会话字符集。
-- 本文件是 UTF-8 编码。而容器内 `docker exec <container> mysql < file.sql`
-- 在 LANG 未设置时 @@character_set_client 会退化为 latin1，服务端就会把
-- 文件里的 UTF-8 字节按 latin1 解释后再转存进 utf8mb4 列 —— 于是
--   '数组'（2 字符）被存成 'æ•°ç»„'，CHAR_LENGTH 从 2 变成 6。
-- 这一行让脚本**自身免疫载入方式**，不依赖调用者记得加 --default-character-set。
-- （表 COMMENT 也会被同样的方式损坏，虽不影响功能但会污染 information_schema。）
-- ------------------------------------------------------------------
SET NAMES utf8mb4;

-- ============================================================
-- 一、认证服务库 judge_auth
-- 只承载 RBAC 与登录流水。账号与密码（BCrypt）在 judge_user.user，
-- 登录时由 judge-auth 经 Feign UserClient 校验 —— 与底座 zx-learn 的拆分保持一致。
-- ============================================================
CREATE DATABASE IF NOT EXISTS `judge_auth` DEFAULT CHARACTER SET utf8mb4;
USE `judge_auth`;

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
CREATE DATABASE IF NOT EXISTS `judge_user` DEFAULT CHARACTER SET utf8mb4;
USE `judge_user`;

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
CREATE DATABASE IF NOT EXISTS `judge_problem` DEFAULT CHARACTER SET utf8mb4;
USE `judge_problem`;

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
CREATE DATABASE IF NOT EXISTS `judge_submission` DEFAULT CHARACTER SET utf8mb4;
USE `judge_submission`;

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
CREATE DATABASE IF NOT EXISTS `judge_contest` DEFAULT CHARACTER SET utf8mb4;
USE `judge_contest`;

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
