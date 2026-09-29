package com.codejudge.user.service;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codejudge.api.dto.user.LoginFormDTO;
import com.codejudge.api.dto.user.UserDTO;
import com.codejudge.common.constants.UserRole;
import com.codejudge.common.domain.PageDTO;
import com.codejudge.common.domain.PageQuery;
import com.codejudge.common.exceptions.BadRequestException;
import com.codejudge.common.exceptions.BizIllegalException;
import com.codejudge.common.exceptions.UnauthorizedException;
import com.codejudge.common.utils.BeanUtils;
import com.codejudge.common.utils.StringUtils;
import com.codejudge.common.utils.UserContext;
import com.codejudge.user.domain.dto.UserFormDTO;
import com.codejudge.user.domain.po.User;
import com.codejudge.user.domain.po.UserDetail;
import com.codejudge.user.domain.vo.UserVO;
import com.codejudge.user.mapper.UserDetailMapper;
import com.codejudge.user.mapper.UserMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 用户服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    /** 用户类型：员工 / 管理员 */
    private static final int TYPE_STAFF = 1;

    /** 禁用账号的 token 吊销纪元存活期 = refresh token 最长寿命（30 天），与 judge-auth 对齐 */
    private static final java.time.Duration REVOCATION_EPOCH_TTL = java.time.Duration.ofDays(30);

    /**
     * 展示型字段（name/username）白名单（QA-B06 修复）：中文、字母、数字、
     * 空格与 · . _ - 分隔符，长度 ≤ 32。
     * <p>这些字段会被管理端列表、个人主页等大量页面原样回显，历史上曾存入
     * {@code <script>alert(1)</script>} 并原样透出（存储型 XSS，前端转义是唯一
     * 兜底）。白名单在入口拒绝，保证任何新消费端（App/邮件/富文本）不触雷；
     * 拒绝而非转义，避免「存库的是转义态、展示端二次转义」的双重编码问题。
     */
    private static final java.util.regex.Pattern DISPLAY_NAME_PATTERN =
            java.util.regex.Pattern.compile("^[\\u4e00-\\u9fa5A-Za-z0-9· ._\\-]{1,32}$");

    private final UserMapper userMapper;
    private final UserDetailMapper userDetailMapper;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;

    /**
     * 管理员重置密码后的统一初始口令，取自环境变量 {@code CJ_USER_DEFAULT_PASSWORD}。
     * <p>
     * <b>刻意不给默认值</b>（历史实现里兜底为 123456）。重置密码的本质是把一个已知字符串
     * 写进<i>别人的</i>账号 —— 一旦这个字符串可预测，等于给全站用户留了统一后门。
     * 因此未配置时不做静默降级，而是让重置动作 fail-closed 并返回可读原因
     * （见 {@link #resetPassword(Long)}）。若运维脚本依赖"重置后必为某固定值"，
     * 必须显式在 .env 中配置之。
     */
    @Value("${CJ_USER_DEFAULT_PASSWORD:}")
    private String defaultPassword;

    /**
     * 启动期一次性自检：重置口令是否可用、强度是否足够。
     * <p>只报告"是否已配置 / 长度"，不打印口令本身（日志会长期留存）。
     */
    @PostConstruct
    void reportDefaultPasswordAvailability() {
        if (StringUtils.isBlank(defaultPassword)) {
            log.warn("⚠ CJ_USER_DEFAULT_PASSWORD 未配置：管理员「重置密码」接口将直接返回 400，"
                            + "不再降级为固定弱口令。如需启用该功能，请在 .env 配置随机强口令后重启。");
        } else if (defaultPassword.trim().length() < 8) {
            log.warn("⚠ CJ_USER_DEFAULT_PASSWORD 已配置但长度仅 {}，强度不足（建议 ≥ 16 位随机串）；"
                            + "自检脚本：python scripts/check-hardcoded-defaults.py",
                    defaultPassword.trim().length());
        }
    }

    /**
     * 登录校验（供认证服务调用）
     */
    public UserDTO queryUserDetail(LoginFormDTO loginFormDTO, boolean isStaff) {
        String cellPhone = loginFormDTO.getCellPhone();
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getCellPhone, cellPhone));
        if (user == null) {
            throw new UnauthorizedException("用户名或密码错误");
        }
        if (!BCrypt.checkpw(loginFormDTO.getPassword(), user.getPassword())) {
            throw new UnauthorizedException("用户名或密码错误");
        }
        if (isStaff && user.getType() != 1) {
            throw new UnauthorizedException("非管理员账号");
        }
        return BeanUtils.copyBean(user, UserDTO.class);
    }

    public List<UserDTO> queryUserByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<User> users = userMapper.selectBatchIds(ids);
        return BeanUtils.copyList(users, UserDTO.class);
    }

    public Integer queryUserType(Long id) {
        User user = userMapper.selectById(id);
        return user == null ? null : user.getType();
    }

    /**
     * 查询用户状态（1 正常 / 其他为禁用），供 judge-auth 续签 token 前校验。
     * 用户不存在返回 null，调用方按 fail-closed 处理。
     */
    public Integer queryUserStatus(Long id) {
        User user = userMapper.selectById(id);
        return user == null ? null : user.getStatus();
    }

    public Map<String, Long> exchangeUserId(String phone) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getCellPhone, phone));
        return user == null ? Map.of() : Map.of("userId", user.getId());
    }

    public UserVO queryMe() {
        Long userId = UserContext.getUserId();
        if (userId == 0) {
            throw new UnauthorizedException("未登录");
        }
        return queryById(userId);
    }

    public UserVO queryById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BadRequestException("用户不存在");
        }
        // BUG-001 修复（QA-E06）：原先对任意登录用户全量下发 cellPhone/email/detail，
        // 构成水平越权 PII 泄露。按视角裁剪：本人 / STAFF / TEACHER / 服务间直连取全量，
        // 其余（学员查看他人）走公开视图，隐私字段不下发。
        if (!canViewFullProfile(id)) {
            return UserVO.ofPublic(user);
        }
        UserDetail detail = userDetailMapper.selectOne(
                new LambdaQueryWrapper<UserDetail>().eq(UserDetail::getUserId, id));
        return UserVO.of(user, detail);
    }

    /**
     * 全量资料可见性判定（与 OwnerAccessGuard 同源语义）：
     * 无 user-info 头 = 服务间 Feign 直连（网关流量必带该头），放行；
     * 本人放行；STAFF/TEACHER 放行；其余 fail-closed 只给公开视图。
     */
    private boolean canViewFullProfile(Long id) {
        Long current = UserContext.getUser();
        if (current == null || current.equals(id)) {
            return true;
        }
        return UserContext.hasRole(UserRole.STAFF.getCode(), UserRole.TEACHER.getCode());
    }

    /** 校验展示型字段（QA-B06 修复）；null/空串不拦（必填性由各入口自行负责） */
    private void validateDisplayField(String value, String label) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!DISPLAY_NAME_PATTERN.matcher(value).matches()) {
            throw new BadRequestException(label + "仅支持中英文、数字与 · . _ - 空格，且不超过 32 字符");
        }
    }

    public void saveUser(UserFormDTO form) {
        validateDisplayField(form.getName(), "姓名");
        validateDisplayField(form.getUsername(), "用户名");
        checkCellPhone(form.getCellPhone(), null);
        User user = BeanUtils.copyBean(form, User.class);
        if (StringUtils.isNotBlank(form.getPassword())) {
            user.setPassword(BCrypt.hashpw(form.getPassword()));
        }
        if (user.getStatus() == null) {
            user.setStatus(1);
        }
        if (user.getType() == null) {
            user.setType(2);
        }
        userMapper.insert(user);
    }

    public void updateUser(Long id, UserFormDTO form) {
        validateDisplayField(form.getName(), "姓名");
        validateDisplayField(form.getUsername(), "用户名");
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BadRequestException("用户不存在");
        }
        if (StringUtils.isNotBlank(form.getPassword())) {
            user.setPassword(BCrypt.hashpw(form.getPassword()));
        }
        if (StringUtils.isNotBlank(form.getName())) {
            user.setName(form.getName());
        }
        if (form.getStatus() != null) {
            user.setStatus(form.getStatus());
        }
        if (StringUtils.isNotBlank(form.getIcon())) {
            user.setIcon(form.getIcon());
        }
        if (StringUtils.isNotBlank(form.getEmail())) {
            user.setEmail(form.getEmail());
        }
        userMapper.updateById(user);
    }

    /**
     * 用户自助更新个人资料：只放行展示性字段，永不触碰 password/status/type/cellPhone/username。
     * <p>与 {@link #updateUser}（STAFF 管理端点）刻意分离 —— 自助入口若复用管理逻辑，
     * "加字段顺手带上密码"这类回归会直接变成越权写。
     */
    public void updateProfile(Long userId, UserFormDTO form) {
        validateDisplayField(form.getName(), "姓名");
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BadRequestException("用户不存在");
        }
        if (StringUtils.isNotBlank(form.getName())) {
            user.setName(form.getName());
        }
        if (StringUtils.isNotBlank(form.getIcon())) {
            user.setIcon(form.getIcon());
        }
        if (StringUtils.isNotBlank(form.getEmail())) {
            user.setEmail(form.getEmail());
        }
        userMapper.updateById(user);
    }

    /**
     * 管理员重置密码：统一重置为环境变量 {@code CJ_USER_DEFAULT_PASSWORD} 指定的值，
     * BCrypt 加密后落库。
     * <p>未配置该变量时<b>拒绝执行</b>并返回可读原因（fail-closed）—— 见
     * {@link #defaultPassword} 的说明：静默写一个可预测的口令，比一次明确的 400 危险得多。
     */
    public void resetPassword(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BadRequestException("用户不存在");
        }
        String raw = StringUtils.isBlank(defaultPassword) ? "" : defaultPassword.trim();
        if (raw.isEmpty()) {
            throw new BizIllegalException(
                    "重置密码功能未启用：请先在 .env 中配置 CJ_USER_DEFAULT_PASSWORD 并重启服务");
        }
        user.setPassword(BCrypt.hashpw(raw));
        userMapper.updateById(user);
        log.info("管理员重置密码：targetUserId={}, operatorId={}", id, UserContext.getUserId());
    }

    /**
     * 启用 / 禁用账号（管理员权限）。
     * <p>
     * 安全约束（与 {@link #deleteUser} 对齐，避免把系统锁死）：
     * <ul>
     *   <li>不允许禁用当前登录账号 —— 否则管理员一步操作后自己就被踢出；</li>
     *   <li>不允许禁用最后一名启用中的管理员 —— 否则系统再无可用管理入口。</li>
     * </ul>
     * 被禁用的账号在登录时会被 judge-auth 拦截，前端提示"请联系管理员"。
     */
    public void updateStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BadRequestException("状态值不合法：0-禁用 1-启用");
        }
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BadRequestException("用户不存在");
        }
        if (status == 0) {
            Long currentUserId = UserContext.getUserId();
            if (currentUserId != null && currentUserId.equals(id)) {
                throw new BizIllegalException("不能禁用当前登录账号");
            }
            if (Integer.valueOf(TYPE_STAFF).equals(user.getType())) {
                Long enabledStaff = userMapper.selectCount(new LambdaQueryWrapper<User>()
                        .eq(User::getType, TYPE_STAFF)
                        .eq(User::getStatus, 1)
                        .ne(User::getId, id));
                if (enabledStaff == null || enabledStaff == 0) {
                    throw new BizIllegalException("系统至少保留一名启用状态的管理员，无法禁用");
                }
            }
        }
        user.setStatus(status);
        userMapper.updateById(user);
        log.info("账号状态变更：targetUserId={}, status={}, operatorId={}",
                id, status, UserContext.getUserId());
        if (status == 0) {
            // 禁用即时生效：写入用户级吊销纪元（key 常量见 judge-common AuthRedisKeys），
            // 网关据此拒绝 iat 早于纪元的 access token，judge-auth 续签入口据此拒绝 refresh
            // token —— 否则在途 token 仍能按剩余寿命使用（access ≤30min / refresh 30 天）。
            try {
                redis.opsForValue().set(
                        com.codejudge.common.constants.AuthRedisKeys.USER_REVOKED_BEFORE_PREFIX + id,
                        // 纪元对齐下一秒边界：iat 是秒级精度，详见 AuthRedisKeys.nextRevocationEpoch
                        String.valueOf(com.codejudge.common.constants.AuthRedisKeys.nextRevocationEpoch()),
                        REVOCATION_EPOCH_TTL);
            } catch (Exception e) {
                // 不阻断禁用主流程：Redis 故障时在途 token 存活至自然过期，refresh 侧仍有
                // 续签状态校验兜底；由日志告警发现
                log.error("禁用账号的 token 吊销纪元写入失败：targetUserId={}", id, e);
            }
        }
    }

    /**
     * 删除用户（管理员权限）。
     * <p>
     * 安全约束：
     * <ul>
     *   <li>不允许删除当前登录账号，避免管理员误操作后立刻失去登录态；</li>
     *   <li>不允许删除最后一名管理员（type=1），避免系统失去可管理的账号；</li>
     *   <li>用户表按 {@code deleted} 逻辑删除，同时物理清理其用户详情扩展记录，
     *       避免 user_detail 残留孤儿数据。</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BadRequestException("用户不存在");
        }
        Long currentUserId = UserContext.getUserId();
        if (currentUserId != null && currentUserId.equals(id)) {
            throw new BizIllegalException("不能删除当前登录账号");
        }
        if (Integer.valueOf(TYPE_STAFF).equals(user.getType())) {
            Long staffCount = userMapper.selectCount(
                    new LambdaQueryWrapper<User>().eq(User::getType, TYPE_STAFF));
            if (staffCount != null && staffCount <= 1) {
                throw new BizIllegalException("系统至少保留一名管理员，无法删除");
            }
        }
        // 清理扩展信息，再逻辑删除主表
        userDetailMapper.delete(new LambdaQueryWrapper<UserDetail>().eq(UserDetail::getUserId, id));
        userMapper.deleteById(id);
    }

    public void checkCellPhone(String cellPhone, Long excludeId) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .eq(User::getCellPhone, cellPhone);
        if (excludeId != null) {
            wrapper.ne(User::getId, excludeId);
        }
        Long count = userMapper.selectCount(wrapper);
        if (count > 0) {
            throw new BizIllegalException("手机号已存在");
        }
    }

    /**
     * 用户自助改密：显式判空（原实现 null 直接进 BCrypt.checkpw 抛 NPE → 500），
     * 新密码强度与注册规则对齐（≥6 位），并设上限 64 位（hutool BCrypt 对超 72 字节口令静默截断）。
     */
    public void changePassword(String oldPassword, String newPassword) {
        if (StringUtils.isBlank(oldPassword) || StringUtils.isBlank(newPassword)) {
            throw new BadRequestException("原密码与新密码不能为空");
        }
        if (newPassword.trim().length() < 6) {
            throw new BadRequestException("新密码至少 6 位");
        }
        if (newPassword.trim().length() > 64) {
            throw new BadRequestException("新密码最长 64 位");
        }
        Long userId = UserContext.getUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new UnauthorizedException("未登录");
        }
        if (!BCrypt.checkpw(oldPassword, user.getPassword())) {
            throw new BadRequestException("原密码错误");
        }
        user.setPassword(BCrypt.hashpw(newPassword));
        userMapper.updateById(user);
    }

    /**
     * 角色化用户分页查询（管理端 用户/学员/教师 列表共用）。
     *
     * <p>P1 时该方法返回**未分页的完整列表**（{@code R<List<UserVO>>}），接口名却是 {@code /page} ——
     * 用户量上去后会把整张表读进内存，属实现缺陷，P2 随用户管理一并修正为真分页。
     *
     * @param type    用户类型：1-员工 2-学员 3-教师
     * @param query   分页参数（pageNo/pageSize/sortBy/isAsc）
     * @param keyword 可选关键字，同时匹配姓名 / 用户名 / 手机号
     */
    public PageDTO<UserVO> pageQueryUsers(Integer type, PageQuery query, String keyword) {
        String kw = StringUtils.isBlank(keyword) ? null : keyword.trim();
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .eq(User::getType, type)
                .and(kw != null, w -> w.like(User::getName, kw)
                        .or().like(User::getUsername, kw)
                        .or().like(User::getCellPhone, kw));
        Page<User> page = userMapper.selectPage(query.toMpPage("id", false), wrapper);
        return PageDTO.of(page, u -> BeanUtils.copyBean(u, UserVO.class));
    }

    // ==================== 首个管理员引导 ====================

    /**
     * 是否存在员工/管理员账号（type=1），供 judge-auth 启动引导判断
     */
    public boolean adminExists() {
        return userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getType, 1)) > 0;
    }

    /**
     * 引导创建首个管理员：仅在不存在任何管理员时插入，密码经 BCrypt 加密后落库。
     * 并发/重复调用做幂等处理，已存在则返回现有管理员。
     */
    public UserDTO createBootstrapAdmin(String username, String cellPhone, String rawPassword) {
        User existing = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getType, 1).last("limit 1"));
        if (existing != null) {
            return BeanUtils.copyBean(existing, UserDTO.class);
        }
        User user = new User();
        user.setUsername(username);
        user.setCellPhone(cellPhone);
        user.setName("管理员");
        user.setType(1);
        user.setStatus(1);
        // 与登录校验一致的 BCrypt 加密（hutool BCrypt）
        user.setPassword(BCrypt.hashpw(rawPassword));
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发创建时手机号/用户名撞主键唯一约束，按幂等处理
            User again = userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .eq(User::getType, 1).last("limit 1"));
            if (again != null) {
                return BeanUtils.copyBean(again, UserDTO.class);
            }
            throw e;
        }
        return BeanUtils.copyBean(user, UserDTO.class);
    }

    /**
     * 首次改密：校验原密码（BCrypt）通过后写入新密码
     */
    public void changeBootstrapPassword(String cellPhone, String oldRawPassword, String newRawPassword) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getCellPhone, cellPhone));
        if (user == null) {
            throw new UnauthorizedException("账号不存在");
        }
        if (!BCrypt.checkpw(oldRawPassword, user.getPassword())) {
            throw new BadRequestException("原密码错误");
        }
        user.setPassword(BCrypt.hashpw(newRawPassword));
        userMapper.updateById(user);
    }
}
