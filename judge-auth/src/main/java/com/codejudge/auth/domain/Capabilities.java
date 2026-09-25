package com.codejudge.auth.domain;

import com.codejudge.common.constants.UserRole;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 能力码登记表（按钮级权限的**唯一权威**）。
 *
 * <h3>为什么需要它</h3>
 * 需求是「无权限的按钮不渲染，按权限分别渲染」。要做到这一点，前端必须先知道
 * 「当前账号能做什么」。历史实现在前端硬编码了 {@code user.canManage / isAdmin / isStudent}
 * 这类角色判定 —— 那是把<b>权限推导</b>放在了前端，后果有三：
 * <ol>
 *   <li>角色语义一变（如新增「助教」），要改的是一堆 .vue 文件；</li>
 *   <li>前端能算出「你有权限」，就等于把权限模型泄露给了客户端，只能靠后端再拦一次兜底；</li>
 *   <li>前端的判定与后端的 {@code @RequireRole} 是两套独立规则，迟早不一致 ——
 *       表现为「按钮点了报 403」或「有权限却没有入口」。</li>
 * </ol>
 *
 * <h3>本类的位置</h3>
 * 能力码由后端计算、经 {@code GET /accounts/me/capabilities} 下发，前端只做**渲染**：
 * 拿到码集合就画，没拿到的按钮直接不渲染。前端不再有任何「角色 → 能做什么」的推导。
 *
 * <h3>与 user.type 的关系</h3>
 * 本表以 {@code user.type}（1 员工/2 学员/3 教师）为**唯一输入**。这与既有链路完全同源：
 * 登录时该值写进 JWT 的 {@code roleId} claim → 网关验签后透传 {@code role-info} 头 →
 * 下游 {@code UserContext.getRole()} 与 {@code @RequireRole} 都读它。
 * 也就是说「接口能不能调」与「按钮画不画」用的是同一个判定依据，不存在两套真相。
 *
 * <p><b>刻意不使用 {@code role} / {@code privilege} 等 RBAC 表</b>：那六张表在
 * {@code sql/seed.sql} 里没有任何种子数据，启用它意味着引入
 * 「user.type」与「account_role」双源，二者一旦漂移就会出现「按钮画了但接口 403」。
 * 详见 docs/CONTEXT.md 的选型记录。RBAC 表仍保留为管理面 CRUD（现状不变）。
 */
public final class Capabilities {

    private Capabilities() {
    }

    /* ==================== 题目 ==================== */

    /** 浏览题库与题目详情 */
    public static final String PROBLEM_VIEW = "problem:view";
    /** 新建题目 */
    public static final String PROBLEM_CREATE = "problem:create";
    /** 编辑题目 */
    public static final String PROBLEM_EDIT = "problem:edit";
    /** 题目管理面（状态列、草稿筛选、只看我的） */
    public static final String PROBLEM_MANAGE = "problem:manage";
    /** 测试用例（含隐藏用例）管理 */
    public static final String PROBLEM_TESTCASE = "problem:testcase";

    /* ==================== 竞赛 ==================== */

    public static final String CONTEST_VIEW = "contest:view";
    /** 报名参赛 */
    public static final String CONTEST_REGISTER = "contest:register";
    public static final String CONTEST_CREATE = "contest:create";
    /** 赛务操作：手动封榜 / 终榜重建 / 快照 / 实时全量榜 */
    public static final String CONTEST_MANAGE = "contest:manage";

    /* ==================== 提交与判题 ==================== */

    public static final String SUBMISSION_CREATE = "submission:create";
    /** 只看得到自己提交的记录 */
    public static final String SUBMISSION_VIEW_OWN = "submission:view-own";
    /** 可跨用户查看提交（「提交人」列、「教师/管理员视图」文案） */
    public static final String SUBMISSION_VIEW_ALL = "submission:view-all";
    /** 重判 */
    public static final String SUBMISSION_REJUDGE = "submission:rejudge";
    /** 可见隐藏用例的输入/输出摘要 */
    public static final String SUBMISSION_HIDDEN_OUTPUT = "submission:hidden-output";

    /* ==================== AI ==================== */

    public static final String AI_REVIEW = "ai:review";
    public static final String KNOWLEDGE_MANAGE = "knowledge:manage";

    /* ==================== 系统配置面（仅员工） ==================== */

    public static final String USER_MANAGE = "user:manage";
    public static final String TAG_MANAGE = "tag:manage";
    public static final String WORKER_VIEW = "worker:view";
    public static final String MONITOR_VIEW = "monitor:view";

    /**
     * 全量能力目录：code → 展示名。
     * <p>用 {@link LinkedHashMap} 固定顺序，保证接口输出稳定、便于前端与文档比对。
     * <p>这里刻意<b>不带分组</b>：分组是「菜单画在哪一栏」的呈现层概念，已由
     * {@code CapabilityService} 的菜单表自带（{@code MenuItemVO.group}），
     * 在此再存一份等于多出一个可能与菜单分叉的真相。
     */
    private static final Map<String, String> CATALOGUE = new LinkedHashMap<>();

    static {
        put(PROBLEM_VIEW, "浏览题目");
        put(PROBLEM_CREATE, "新建题目");
        put(PROBLEM_EDIT, "编辑题目");
        put(PROBLEM_MANAGE, "题目管理视图");
        put(PROBLEM_TESTCASE, "测试用例管理");

        put(CONTEST_VIEW, "浏览竞赛");
        put(CONTEST_REGISTER, "报名参赛");
        put(CONTEST_CREATE, "创建竞赛");
        put(CONTEST_MANAGE, "赛务操作");

        put(SUBMISSION_CREATE, "提交代码");
        put(SUBMISSION_VIEW_OWN, "查看自己的提交");
        put(SUBMISSION_VIEW_ALL, "查看全部提交");
        put(SUBMISSION_REJUDGE, "重判提交");
        put(SUBMISSION_HIDDEN_OUTPUT, "查看隐藏用例输出");

        put(AI_REVIEW, "AI 代码点评");
        put(KNOWLEDGE_MANAGE, "AI 知识库管理");

        put(USER_MANAGE, "用户管理");
        put(TAG_MANAGE, "标签管理");
        put(WORKER_VIEW, "判题集群");
        put(MONITOR_VIEW, "系统监控");
    }

    private static void put(String code, String name) {
        CATALOGUE.put(code, name);
    }

    /** 学员：刷题 + 参赛 + 看自己的提交 + AI 点评 */
    private static final Set<String> STUDENT = setOf(
            PROBLEM_VIEW,
            CONTEST_VIEW, CONTEST_REGISTER,
            SUBMISSION_CREATE, SUBMISSION_VIEW_OWN,
            AI_REVIEW);

    /** 教师：学员能力 + 教学侧（建题、建赛、看全部提交、隐藏用例、知识库） */
    private static final Set<String> TEACHER = merge(STUDENT,
            PROBLEM_CREATE, PROBLEM_EDIT, PROBLEM_MANAGE, PROBLEM_TESTCASE,
            CONTEST_CREATE, CONTEST_MANAGE,
            SUBMISSION_VIEW_ALL, SUBMISSION_REJUDGE, SUBMISSION_HIDDEN_OUTPUT,
            KNOWLEDGE_MANAGE);

    /** 员工（管理员）：全量。用 CATALOGUE 的键集，新增能力码时自动纳入，不会漏配 */
    private static final Set<String> STAFF = Collections.unmodifiableSet(new LinkedHashSet<>(CATALOGUE.keySet()));

    private static Set<String> setOf(String... codes) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(codes)));
    }

    private static Set<String> merge(Set<String> base, String... extra) {
        Set<String> merged = new LinkedHashSet<>(base);
        merged.addAll(List.of(extra));
        return Collections.unmodifiableSet(merged);
    }

    /**
     * 取某个用户类型（{@code user.type}）的能力码集合。
     * <p>未知类型返回空集而非全部 —— fail-closed：拿不准就不给任何按钮，
     * 而不是给满（后者会在 user.type 出现脏值时把管理面按钮画给学员）。
     */
    public static Set<String> of(Integer userType) {
        if (userType == null) {
            return Set.of();
        }
        return switch (userType) {
            case UserRole.STAFF_CODE -> STAFF;
            case UserRole.TEACHER_CODE -> TEACHER;
            case UserRole.STUDENT_CODE -> STUDENT;
            default -> Set.of();
        };
    }

    /** 全量能力目录（code → 展示名），供管理面或接口文档使用 */
    public static Map<String, String> catalogue() {
        return Collections.unmodifiableMap(CATALOGUE);
    }
}
