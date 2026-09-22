package com.codejudge.problem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codejudge.common.constants.UserRole;
import com.codejudge.common.domain.PageDTO;
import com.codejudge.common.exceptions.BadRequestException;
import com.codejudge.common.exceptions.ForbiddenException;
import com.codejudge.common.exceptions.UnauthorizedException;
import com.codejudge.common.utils.OwnerAccessGuard;
import com.codejudge.common.utils.StringUtils;
import com.codejudge.common.utils.UserContext;
import com.codejudge.problem.domain.dto.ProblemFormDTO;
import com.codejudge.problem.domain.dto.ProblemQuery;
import com.codejudge.problem.domain.po.Problem;
import com.codejudge.problem.domain.po.ProblemTag;
import com.codejudge.problem.domain.po.ProblemVersion;
import com.codejudge.problem.domain.po.Tag;
import com.codejudge.problem.domain.po.TestCase;
import com.codejudge.problem.domain.vo.ProblemDetailVO;
import com.codejudge.problem.domain.vo.ProblemVO;
import com.codejudge.problem.domain.vo.ProblemVersionVO;
import com.codejudge.problem.domain.vo.TagVO;
import com.codejudge.problem.domain.vo.TestCaseVO;
import com.codejudge.problem.mapper.ProblemMapper;
import com.codejudge.problem.mapper.ProblemTagMapper;
import com.codejudge.problem.mapper.ProblemVersionMapper;
import com.codejudge.problem.mapper.TagMapper;
import com.codejudge.problem.mapper.TestCaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 题目服务
 *
 * <p>职责：题目主体 + 题面版本 + 用例读取 + 标签关联的读写，以及**可见性隔离**。
 *
 * <h3>三条安全边界（P2 验收重点）</h3>
 * <ol>
 *   <li><b>隐藏用例不下发</b> —— {@link #queryDetail} 只把 {@code is_hidden = 0} 放进
 *       {@code samples}；全量用例仅在归属教师/管理员视角下以 {@code testCases} 返回，
 *       其余视角该字段为 null（不是空数组 —— 空数组会被误读为"没有隐藏用例"）。</li>
 *   <li><b>未发布题目不外泄</b> —— 草稿/下线题对非归属者直接抛 403，而不是返回一个
 *       内容为空的详情，避免"探测到题目存在"这一信息本身泄露。</li>
 *   <li><b>改题必须归属本人</b> —— 所有写操作先过 {@link #getOwnedProblem}，
 *       内部复用底座 {@link OwnerAccessGuard}（以 {@code problem.ownerId} 作为目标 id）。</li>
 * </ol>
 *
 * <h3>为什么删除用例 / 标签关联要走物理删除</h3>
 * {@code test_case}、{@code problem_tag} 的唯一键分别是 {@code (problem_id, seq)}、
 * {@code (problem_id, tag_id)}，**都不含 deleted 列**。逻辑删除只置 deleted=1 而保留行，
 * 之后用同一 seq / 同一 tag 重新插入就会撞唯一键。因此这几张表走物理删除
 * （见各 Mapper 中的 {@code deletePhysically*} 方法）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProblemService {

    /** 状态：草稿 */
    public static final int STATUS_DRAFT = 0;
    /** 状态：已发布 */
    public static final int STATUS_PUBLISHED = 1;
    /** 状态：已下线 */
    public static final int STATUS_OFFLINE = 2;

    /** 隐藏用例标记 */
    public static final int HIDDEN_YES = 1;

    private static final int DEFAULT_DIFFICULTY = 1;
    private static final int DEFAULT_TIME_LIMIT_MS = 1000;
    private static final int DEFAULT_MEMORY_LIMIT_MB = 256;
    private static final int MAX_TITLE_LENGTH = 255;

    private final ProblemMapper problemMapper;
    private final ProblemVersionMapper problemVersionMapper;
    private final TestCaseMapper testCaseMapper;
    private final TagMapper tagMapper;
    private final ProblemTagMapper problemTagMapper;

    // ==================== 写：题目 ====================

    /**
     * 建题（教师 / 管理员）。同时落库题目主体与 v1 题面，保证不会出现「有题无面」的中间态。
     *
     * @return 新建题目 id
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createProblem(ProblemFormDTO form) {
        if (form == null) {
            throw new BadRequestException("题目信息不能为空");
        }
        validateTitle(form.getTitle());
        checkDifficulty(form.getDifficulty());
        checkTimeLimit(form.getTimeLimitMs());
        checkMemoryLimit(form.getMemoryLimitMb());
        checkStatus(form.getStatus());

        Long currentUserId = requireLogin();

        Problem problem = new Problem();
        problem.setTitle(form.getTitle().trim());
        problem.setDifficulty(form.getDifficulty() == null ? DEFAULT_DIFFICULTY : form.getDifficulty());
        problem.setTimeLimitMs(form.getTimeLimitMs() == null ? DEFAULT_TIME_LIMIT_MS : form.getTimeLimitMs());
        problem.setMemoryLimitMb(form.getMemoryLimitMb() == null ? DEFAULT_MEMORY_LIMIT_MB : form.getMemoryLimitMb());
        // 建题默认草稿：避免未完成的题面直接对学员可见
        problem.setStatus(form.getStatus() == null ? STATUS_DRAFT : form.getStatus());
        problem.setOwnerId(currentUserId);
        problem.setSubmitCount(0);
        problem.setAcceptedCount(0);
        problem.setCreater(currentUserId);
        problemMapper.insert(problem);

        ProblemVersion version = new ProblemVersion();
        version.setProblemId(problem.getId());
        version.setVersionNo(1);
        applyVersionContent(version, form, null);
        version.setCreatedBy(currentUserId);
        version.setCreater(currentUserId);
        problemVersionMapper.insert(version);

        problem.setCurrentVersionId(version.getId());
        problemMapper.updateById(problem);

        replaceTags(problem.getId(), form.getTagIds(), currentUserId);

        log.info("建题成功：problemId={}, title={}, ownerId={}", problem.getId(), problem.getTitle(), currentUserId);
        return problem.getId();
    }

    /**
     * 改题（归属教师 / 管理员）。
     *
     * <p>分段语义：
     * <ul>
     *   <li>主体字段（标题/难度/限制/状态）非空才更新 —— 支持前端只改一个字段；</li>
     *   <li>题面字段任一非空 → **追加一条新版本**，并切换 {@code current_version_id}。
     *       这是"历史提交可复现"的关键：老版本不被覆盖；</li>
     *   <li>{@code tagIds} 传 null 表示不动标签，传空数组表示清空。</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateProblem(Long id, ProblemFormDTO form) {
        if (form == null) {
            throw new BadRequestException("题目信息不能为空");
        }
        Problem problem = getOwnedProblem(id);

        if (form.getTitle() != null) {
            validateTitle(form.getTitle());
            problem.setTitle(form.getTitle().trim());
        }
        if (form.getDifficulty() != null) {
            checkDifficulty(form.getDifficulty());
            problem.setDifficulty(form.getDifficulty());
        }
        if (form.getTimeLimitMs() != null) {
            checkTimeLimit(form.getTimeLimitMs());
            problem.setTimeLimitMs(form.getTimeLimitMs());
        }
        if (form.getMemoryLimitMb() != null) {
            checkMemoryLimit(form.getMemoryLimitMb());
            problem.setMemoryLimitMb(form.getMemoryLimitMb());
        }
        if (form.getStatus() != null) {
            checkStatus(form.getStatus());
            problem.setStatus(form.getStatus());
        }
        problem.setUpdater(UserContext.getUser());
        problemMapper.updateById(problem);

        if (hasVersionContent(form)) {
            ProblemVersion previous = loadCurrentVersion(problem);
            ProblemVersion version = new ProblemVersion();
            version.setProblemId(id);
            version.setVersionNo(nextVersionNo(id));
            // 未提交的题面字段沿用上一版本，避免"只改标题却把题面清空"
            applyVersionContent(version, form, previous);
            version.setCreatedBy(UserContext.getUser());
            version.setCreater(UserContext.getUser());
            problemVersionMapper.insert(version);

            problem.setCurrentVersionId(version.getId());
            problemMapper.updateById(problem);
            log.info("题目生成新版本：problemId={}, versionNo={}, operatorId={}",
                    id, version.getVersionNo(), UserContext.getUserId());
        }

        if (form.getTagIds() != null) {
            replaceTags(id, form.getTagIds(), UserContext.getUser());
        }
    }

    /**
     * 发布 / 下线 / 转草稿（归属教师 / 管理员）。
     */
    public void updateStatus(Long id, Integer status) {
        if (status == null) {
            throw new BadRequestException("状态不能为空");
        }
        checkStatus(status);
        Problem problem = getOwnedProblem(id);
        problem.setStatus(status);
        problem.setUpdater(UserContext.getUser());
        problemMapper.updateById(problem);
        log.info("题目状态变更：problemId={}, status={}, operatorId={}",
                id, status, UserContext.getUserId());
    }

    /**
     * 删除题目（归属教师 / 管理员）。
     *
     * <p>用例与标签关联**物理删除**（唯一键不含 deleted，逻辑删除会锁死 seq/标签复用），
     * 题目主体与题面版本保留逻辑删除痕迹以便追溯。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteProblem(Long id) {
        Problem problem = getOwnedProblem(id);
        testCaseMapper.deletePhysicallyByProblemId(id);
        problemTagMapper.deletePhysicallyByProblemId(id);
        problemMapper.deleteById(id);
        log.info("删除题目：problemId={}, title={}, operatorId={}",
                id, problem.getTitle(), UserContext.getUserId());
    }

    // ==================== 读：题目 ====================

    /**
     * 题目分页（可见范围按角色收敛）。
     *
     * <table border="1">
     *   <caption>可见范围</caption>
     *   <tr><th>角色</th><th>默认可见</th><th>是否可按状态过滤</th></tr>
     *   <tr><td>学员</td><td>仅 status=1 已发布</td><td>否（传入被忽略）</td></tr>
     *   <tr><td>教师</td><td>已发布 + 自己创建的（含草稿/下线）</td><td>是（限自己题目范围）</td></tr>
     *   <tr><td>管理员</td><td>全部</td><td>是</td></tr>
     * </table>
     *
     * <p>列表**不返回任何用例**（无论隐藏与否），列表页无展示用例的需求，少查一张表就少一个泄露面。
     */
    public PageDTO<ProblemVO> pageQuery(ProblemQuery query) {
        boolean staff = UserContext.hasRole(UserRole.STAFF.getCode());
        boolean teacher = UserContext.hasRole(UserRole.TEACHER.getCode());
        Integer statusFilter = query.getStatus();
        boolean onlyMine = Boolean.TRUE.equals(query.getOnlyMine());

        LambdaQueryWrapper<Problem> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.isNotBlank(query.getKeyword())) {
            wrapper.like(Problem::getTitle, query.getKeyword().trim());
        }
        if (query.getDifficulty() != null) {
            wrapper.eq(Problem::getDifficulty, query.getDifficulty());
        }

        if (onlyMine) {
            // 「我的题目」：草稿与下线题也可自见
            wrapper.eq(Problem::getOwnerId, requireLogin());
            if (statusFilter != null) {
                wrapper.eq(Problem::getStatus, statusFilter);
            }
        } else if (staff) {
            if (statusFilter != null) {
                wrapper.eq(Problem::getStatus, statusFilter);
            }
        } else if (teacher) {
            if (statusFilter != null) {
                // 按状态过滤时收敛到自己的题目 —— 否则教师能通过 status=0 枚举出他人草稿
                wrapper.eq(Problem::getStatus, statusFilter)
                        .eq(Problem::getOwnerId, requireLogin());
            } else {
                Long me = requireLogin();
                wrapper.and(w -> w.eq(Problem::getStatus, STATUS_PUBLISHED)
                        .or().eq(Problem::getOwnerId, me));
            }
        } else {
            // 学员 / 其它角色：只见已发布，status 参数被忽略
            wrapper.eq(Problem::getStatus, STATUS_PUBLISHED);
        }

        if (query.getTagId() != null) {
            List<Long> problemIds = problemTagMapper.selectList(
                            new LambdaQueryWrapper<ProblemTag>().eq(ProblemTag::getTagId, query.getTagId()))
                    .stream().map(ProblemTag::getProblemId).toList();
            if (problemIds.isEmpty()) {
                return PageDTO.empty(0L, 0L);
            }
            wrapper.in(Problem::getId, problemIds);
        }

        Page<Problem> page = problemMapper.selectPage(query.toMpPage("id", false), wrapper);

        List<ProblemVO> list = new ArrayList<>();
        if (!page.getRecords().isEmpty()) {
            // 批量装配标签，避免逐题查询造成 N+1
            Map<Long, List<TagVO>> tagMap = loadTagsByProblems(
                    page.getRecords().stream().map(Problem::getId).toList());
            for (Problem p : page.getRecords()) {
                ProblemVO vo = ProblemVO.of(p);
                vo.setTags(tagMap.getOrDefault(p.getId(), List.of()));
                list.add(vo);
            }
        }
        return PageDTO.of(page, list);
    }

    /**
     * 题目详情（含题面；用例按视角分级下发，见类注释的安全边界 1、2）。
     */
    public ProblemDetailVO queryDetail(Long id) {
        Problem problem = problemMapper.selectById(id);
        if (problem == null) {
            throw new BadRequestException("题目不存在");
        }
        boolean owner = isOwnerOrStaff(problem);
        if (!Integer.valueOf(STATUS_PUBLISHED).equals(problem.getStatus()) && !owner) {
            // 未发布题目对非归属者等同不存在，不返回空壳详情
            throw new ForbiddenException("题目不存在或未发布");
        }

        ProblemDetailVO vo = ProblemVO.fill(new ProblemDetailVO(), problem);

        ProblemVersion version = loadCurrentVersion(problem);
        if (version != null) {
            vo.setVersionNo(version.getVersionNo());
            vo.setStatement(version.getStatement());
            vo.setInputSpec(version.getInputSpec());
            vo.setOutputSpec(version.getOutputSpec());
            vo.setHint(version.getHint());
            vo.setTemplateCode(version.getTemplateCode());
        }

        List<TestCase> cases = testCaseMapper.selectList(new LambdaQueryWrapper<TestCase>()
                .eq(TestCase::getProblemId, id)
                .orderByAsc(TestCase::getSeq));

        // 无条件下发：可见样例
        vo.setSamples(cases.stream().filter(c -> !isHidden(c)).map(TestCaseVO::of).toList());
        // 仅归属教师 / 管理员下发：全量用例与隐藏用例数量（null = 无权限，勿改成空数组）
        if (owner) {
            vo.setTestCases(cases.stream().map(TestCaseVO::of).toList());
            vo.setHiddenCaseCount((int) cases.stream().filter(this::isHidden).count());
        }

        vo.setTags(loadTags(id));
        return vo;
    }

    /**
     * 题面版本历史（归属教师 / 管理员）。
     * <p>版本里含完整题面与模板代码，等同题目所有权内容，故按归属收敛。
     */
    public List<ProblemVersionVO> queryVersions(Long id) {
        getOwnedProblem(id);
        List<ProblemVersion> versions = problemVersionMapper.selectList(
                new LambdaQueryWrapper<ProblemVersion>()
                        .eq(ProblemVersion::getProblemId, id)
                        .orderByDesc(ProblemVersion::getVersionNo));
        return versions.stream().map(v -> {
            ProblemVersionVO vo = new ProblemVersionVO();
            vo.setId(v.getId());
            vo.setProblemId(v.getProblemId());
            vo.setVersionNo(v.getVersionNo());
            vo.setStatement(v.getStatement());
            vo.setInputSpec(v.getInputSpec());
            vo.setOutputSpec(v.getOutputSpec());
            vo.setHint(v.getHint());
            vo.setTemplateCode(v.getTemplateCode());
            vo.setCreatedBy(v.getCreatedBy());
            vo.setCreateTime(v.getCreateTime());
            return vo;
        }).toList();
    }

    // ==================== 归属与可见性 ====================

    /**
     * 取出题目并校验归属（写操作入口）。
     *
     * <p>复用底座 {@link OwnerAccessGuard#checkOwnerOrInternal(Long)} —— 把
     * {@code problem.ownerId} 当作"目标用户 id"传入，其语义恰好是：
     * 「当前用户 == 归属者，或 STAFF；无 user-info 头的服务间调用放行」。
     * 这样不需要为题目再造一个等价守卫。
     */
    public Problem getOwnedProblem(Long problemId) {
        Problem problem = problemMapper.selectById(problemId);
        if (problem == null) {
            throw new BadRequestException("题目不存在");
        }
        OwnerAccessGuard.checkOwnerOrInternal(problem.getOwnerId());
        return problem;
    }

    /**
     * 当前请求者是否可视为题目归属方（归属教师本人或管理员；服务间内部调用视为可）。
     * <p>用于详情接口的"是否下发隐藏用例"判断，使用非抛异常的布尔形式。
     */
    public boolean isOwnerOrStaff(Problem problem) {
        Long current = UserContext.getUser();
        if (current == null) {
            // 服务间内部调用（无 user-info 头）
            return true;
        }
        if (UserContext.hasRole(UserRole.STAFF.getCode())) {
            return true;
        }
        return current.equals(problem.getOwnerId());
    }

    // ==================== 内部工具 ====================

    /**
     * 用例是否为隐藏用例。{@code is_hidden} 可能为 null（历史数据），按可见处理。
     */
    public boolean isHidden(TestCase c) {
        return Integer.valueOf(HIDDEN_YES).equals(c.getIsHidden());
    }

    private Long requireLogin() {
        Long userId = UserContext.getUserId();
        if (userId == null || userId == 0L) {
            throw new UnauthorizedException("未登录");
        }
        return userId;
    }

    private void validateTitle(String title) {
        if (StringUtils.isBlank(title)) {
            throw new BadRequestException("题目标题不能为空");
        }
        if (title.trim().length() > MAX_TITLE_LENGTH) {
            throw new BadRequestException("题目标题长度不能超过 " + MAX_TITLE_LENGTH);
        }
    }

    private void checkDifficulty(Integer difficulty) {
        if (difficulty != null && (difficulty < 1 || difficulty > 5)) {
            throw new BadRequestException("难度取值必须在 1~5 之间");
        }
    }

    private void checkTimeLimit(Integer ms) {
        if (ms != null && (ms < 1 || ms > 60000)) {
            throw new BadRequestException("时间限制必须在 1~60000 毫秒之间");
        }
    }

    private void checkMemoryLimit(Integer mb) {
        if (mb != null && (mb < 1 || mb > 4096)) {
            throw new BadRequestException("内存限制必须在 1~4096 MB 之间");
        }
    }

    private void checkStatus(Integer status) {
        if (status != null && status != STATUS_DRAFT && status != STATUS_PUBLISHED && status != STATUS_OFFLINE) {
            throw new BadRequestException("状态取值不合法：0-草稿 1-已发布 2-已下线");
        }
    }

    /** 题面内容是否有任一字段被提交（决定是否生成新版本） */
    private boolean hasVersionContent(ProblemFormDTO form) {
        return form.getStatement() != null
                || form.getInputSpec() != null
                || form.getOutputSpec() != null
                || form.getHint() != null
                || form.getTemplateCode() != null;
    }

    /**
     * 填充题面版本内容：表单里非 null 的字段覆盖，null 的沿用 {@code fallback}（上一版本）。
     */
    private void applyVersionContent(ProblemVersion target, ProblemFormDTO form, ProblemVersion fallback) {
        target.setStatement(pick(form.getStatement(), fallback == null ? null : fallback.getStatement()));
        target.setInputSpec(pick(form.getInputSpec(), fallback == null ? null : fallback.getInputSpec()));
        target.setOutputSpec(pick(form.getOutputSpec(), fallback == null ? null : fallback.getOutputSpec()));
        target.setHint(pick(form.getHint(), fallback == null ? null : fallback.getHint()));
        target.setTemplateCode(form.getTemplateCode() != null
                ? form.getTemplateCode()
                : (fallback == null ? null : fallback.getTemplateCode()));
    }

    private String pick(String incoming, String fallback) {
        return incoming != null ? incoming : fallback;
    }

    /** 当前版本：优先取 {@code current_version_id}，悬空时回退到版本号最大的那条 */
    private ProblemVersion loadCurrentVersion(Problem problem) {
        if (problem.getCurrentVersionId() != null) {
            ProblemVersion version = problemVersionMapper.selectById(problem.getCurrentVersionId());
            if (version != null) {
                return version;
            }
        }
        return problemVersionMapper.selectOne(new LambdaQueryWrapper<ProblemVersion>()
                .eq(ProblemVersion::getProblemId, problem.getId())
                .orderByDesc(ProblemVersion::getVersionNo)
                .last("limit 1"));
    }

    private int nextVersionNo(Long problemId) {
        ProblemVersion latest = problemVersionMapper.selectOne(new LambdaQueryWrapper<ProblemVersion>()
                .eq(ProblemVersion::getProblemId, problemId)
                .orderByDesc(ProblemVersion::getVersionNo)
                .last("limit 1"));
        return latest == null ? 1 : latest.getVersionNo() + 1;
    }

    /**
     * 全量覆盖题目标签。
     *
     * <p>先物理清空再逐条插入（唯一键 {@code uk_problem_tag} 不含 deleted，不能走逻辑删除）。
     * {@code tagIds == null} 表示"不改动"，空列表表示"清空"，两者语义不同，勿合并。
     */
    private void replaceTags(Long problemId, List<Long> tagIds, Long operator) {
        if (tagIds == null) {
            return;
        }
        problemTagMapper.deletePhysicallyByProblemId(problemId);
        if (tagIds.isEmpty()) {
            return;
        }
        List<Long> distinct = tagIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return;
        }
        List<Tag> found = tagMapper.selectBatchIds(distinct);
        if (found.size() != distinct.size()) {
            Set<Long> foundIds = found.stream().map(Tag::getId).collect(Collectors.toSet());
            List<Long> missing = distinct.stream().filter(x -> !foundIds.contains(x)).toList();
            throw new BadRequestException("标签不存在：" + missing);
        }
        for (Long tagId : distinct) {
            ProblemTag rel = new ProblemTag();
            rel.setProblemId(problemId);
            rel.setTagId(tagId);
            rel.setCreater(operator);
            problemTagMapper.insert(rel);
        }
    }

    private List<TagVO> loadTags(Long problemId) {
        return loadTagsByProblems(List.of(problemId)).getOrDefault(problemId, List.of());
    }

    /** 批量装配题目标签：一次查关联表 + 一次查标签表，避免列表页 N+1 */
    private Map<Long, List<TagVO>> loadTagsByProblems(List<Long> problemIds) {
        if (problemIds == null || problemIds.isEmpty()) {
            return Map.of();
        }
        List<ProblemTag> rels = problemTagMapper.selectList(new LambdaQueryWrapper<ProblemTag>()
                .in(ProblemTag::getProblemId, problemIds));
        if (rels.isEmpty()) {
            return Map.of();
        }
        Set<Long> tagIds = rels.stream().map(ProblemTag::getTagId).collect(Collectors.toSet());
        Map<Long, Tag> tags = tagMapper.selectBatchIds(tagIds).stream()
                .collect(Collectors.toMap(Tag::getId, t -> t));

        Map<Long, List<TagVO>> result = new HashMap<>();
        for (ProblemTag rel : rels) {
            Tag tag = tags.get(rel.getTagId());
            if (tag == null) {
                continue;
            }
            result.computeIfAbsent(rel.getProblemId(), k -> new ArrayList<>())
                    .add(TagVO.of(tag.getId(), tag.getName(), tag.getType()));
        }
        return result;
    }
}
