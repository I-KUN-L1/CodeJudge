package com.codejudge.auth.service;

import com.codejudge.api.client.user.UserClient;
import com.codejudge.api.dto.user.BootstrapAdminDTO;
import com.codejudge.api.dto.user.PasswordChangeDTO;
import com.codejudge.common.exceptions.CommonException;
import feign.codec.DecodeException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 首个管理员安全引导：
 * <p>服务启动时若无管理员账号，则创建首个管理员（以 BCrypt 加密入库），
 * 同时将初始凭据写入应用根目录的 {@code .bootstrap-credentials} 文件（应被 git 忽略）。</p>
 * <p>初始密码来源，按优先级：</p>
 * <ol>
 *   <li>环境变量 {@code CJ_ADMIN_INIT_PASSWORD}（生产/团队环境应显式配置）；</li>
 *   <li>未配置时 —— <b>生成一次性随机强口令</b>，只写入凭据文件、不进日志。</li>
 * </ol>
 * <p>历史实现里此处兜底为硬编码弱口令 {@code 123456}，且漏配时<b>不报错、不留痕</b>，
 * 属"静默失效"：等于给每个漏配的部署都发了一个人尽皆知的管理员账号。现改为
 * fail-safe —— 宁可让运维去凭据文件里取一次口令，也不接受可预测的管理员口令。</p>
 * <p>登录后应立即通过 POST /accounts/password/first-change 修改密码（成功后凭据文件自动删除）。</p>
 */
@Slf4j
@Service
public class AdminBootstrapService {

    /** 随机初始口令长度（不含前缀） */
    private static final int RANDOM_PASSWORD_LENGTH = 24;

    /** 随机口令字符集：去掉了 0/O/1/l/I 等易混淆字符，便于人工誊抄 */
    private static final char[] PASSWORD_ALPHABET =
            "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserClient userClient;
    private final Path credentialFile;
    private final String adminPhone;
    private final String adminUsername;
    /** 显式配置的初始口令；为空表示"由本服务随机生成" */
    private final String configuredInitPassword;

    public AdminBootstrapService(UserClient userClient,
                                 @Value("${cj.admin-bootstrap.credential-file:.bootstrap-credentials}") String credentialFile,
                                 @Value("${cj.admin-bootstrap.admin-phone:13800000000}") String adminPhone,
                                 @Value("${cj.admin-bootstrap.admin-username:admin}") String adminUsername,
                                 @Value("${cj.admin-bootstrap.init-password:}") String initPassword) {
        this.userClient = userClient;
        this.credentialFile = resolve(credentialFile);
        this.adminPhone = adminPhone;
        this.adminUsername = adminUsername;
        this.configuredInitPassword = initPassword == null ? "" : initPassword.trim();
    }

    /**
     * 启动期一次性自检：把「首个管理员口令从哪来」与「凭据文件到底落在哪」显性写进日志。
     * <p>
     * 不打印口令本身（日志会长期留存），只说明来源与强度。这样即便运维漏配
     * {@code CJ_ADMIN_INIT_PASSWORD}，也能在启动日志里立刻看出"本次口令是随机生成的"，
     * 而不会误以为用的是自己配的那个值。
     * </p><p>
     * 凭据文件的<b>绝对路径无条件打印</b>：它的默认值是按进程工作目录解析的相对路径，
     * 换个启动方式（IDE 里从模块目录跑 / 手工 {@code java -jar} / {@code scripts/start-all.py}）
     * 就会落到不同目录。只报"生成过凭据"却不报路径，等于把口令藏了起来 ——
     * 曾因此留下一份孤儿凭据文件而无人察觉。
     * </p>
     */
    @PostConstruct
    void reportInitPasswordSource() {
        boolean present = Files.exists(credentialFile);
        log.info("首个管理员凭据文件：{}（当前{}）", credentialFile.toAbsolutePath(),
                present ? "存在 —— 引导期初始口令尚未被消费" : "不存在");

        if (configuredInitPassword.isEmpty()) {
            log.info("CJ_ADMIN_INIT_PASSWORD 未配置：若本次触发管理员引导，将生成 {} 位随机强口令"
                            + "并写入上述文件（请从该文件读取，不要指望日志）。",
                    RANDOM_PASSWORD_LENGTH);
        } else if (configuredInitPassword.length() < 8) {
            log.warn("⚠ CJ_ADMIN_INIT_PASSWORD 已配置但长度仅 {}，强度不足"
                            + "（建议 ≥ 16 位随机串）；自检脚本：python scripts/check-hardcoded-defaults.py",
                    configuredInitPassword.length());
        }
    }

    /**
     * 引导期初始凭据是否「尚未被消费」——即凭据文件仍在。
     * <p>该文件只在「首个管理员刚被创建 且 还没改密」这个窗口内存在，因此它可以当作
     * 「本部署仍在引导态」的探针：登录响应据此提示用户去改初始密码。
     * <p>刻意只读文件、不解析内容，也不返回路径以外的任何信息 —— 口令本身永不经过接口。
     */
    public boolean isBootstrapPending() {
        return Files.exists(credentialFile);
    }

    /**
     * 启动引导：已存在凭据文件或已存在管理员则跳过；否则落库并写入凭据文件。
     */
    public void bootstrapIfNeeded() {
        if (Files.exists(credentialFile)) {
            log.info("已存在初始凭据文件 {}，跳过管理员引导", credentialFile.toAbsolutePath());
            return;
        }
        if (Boolean.TRUE.equals(userClient.adminExists())) {
            log.info("已存在管理员账号，跳过管理员引导");
            return;
        }
        boolean generated = configuredInitPassword.isEmpty();
        String initPassword = generated ? generateRandomPassword() : configuredInitPassword;
        BootstrapAdminDTO dto = new BootstrapAdminDTO();
        dto.setCellPhone(adminPhone);
        dto.setUsername(adminUsername);
        dto.setPassword(initPassword);
        userClient.createBootstrapAdmin(dto);
        writeCredentialFile(initPassword);
        log.warn("已创建首个管理员（初始口令{}）并写入 {}，"
                        + "首次登录后请立即通过 POST /accounts/password/first-change 修改密码",
                generated ? "为本次随机生成" : "取自 CJ_ADMIN_INIT_PASSWORD",
                credentialFile.toAbsolutePath());
    }

    /**
     * 生成一次性随机强口令：CSPRNG + URL-safe 字符集，避免口令在 properties / shell
     * 语境下被转义或截断。
     */
    private static String generateRandomPassword() {
        StringBuilder sb = new StringBuilder(RANDOM_PASSWORD_LENGTH + 3).append("Cj_");
        for (int i = 0; i < RANDOM_PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_ALPHABET[RANDOM.nextInt(PASSWORD_ALPHABET.length)]);
        }
        return sb.toString();
    }

    /**
     * 首次改密：交由 judge-user 校验原密码（BCrypt）并落库；成功后删除初始凭据文件。
     * <p>feign 将 Decoder 抛出的业务异常包装为 {@link DecodeException}，此处解包还原
     * RDecoder 的业务异常（如"原密码错误"→400），避免被兜底为 500；同时保证校验失败
     * 绝不删除凭据文件（fail-closed）。</p>
     */
    public void changeBootstrapPassword(String cellPhone, String oldPassword, String newPassword) {
        PasswordChangeDTO dto = new PasswordChangeDTO();
        dto.setCellPhone(cellPhone);
        dto.setOldPassword(oldPassword);
        dto.setNewPassword(newPassword);
        try {
            userClient.changeBootstrapPassword(dto);
        } catch (DecodeException e) {
            if (e.getCause() instanceof CommonException ce) {
                throw ce;
            }
            throw e;
        }
        deleteCredentialFile();
    }

    private void writeCredentialFile(String rawPassword) {
        String content = String.join("\n",
                "== CodeJudge 首个管理员初始凭据 ==",
                "生成时间：" + LocalDateTime.now().format(FMT),
                "说明：文件仅首次启动生成，登录后请立即修改密码，修改成功后该文件会被自动删除。",
                "手机号/账号：" + adminPhone,
                "初始密码：" + rawPassword + "\n");
        try {
            Files.createDirectories(credentialFile.toAbsolutePath().getParent() == null ? Paths.get(".")
                    : credentialFile.toAbsolutePath().getParent());
            Files.write(credentialFile, content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 凭据落盘失败不阻断鉴权主流程，但需提示
            throw new IllegalStateException("写入初始凭据文件失败：" + credentialFile.toAbsolutePath(), e);
        }
    }

    private void deleteCredentialFile() {
        try {
            Files.deleteIfExists(credentialFile);
            log.info("首次改密成功，已删除初始凭据文件 {}", credentialFile.toAbsolutePath());
        } catch (IOException e) {
            log.warn("删除初始凭据文件失败，请手动删除 {}", credentialFile.toAbsolutePath(), e);
        }
    }

    private static Path resolve(String file) {
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("credential-file 不能为空");
        }
        Path path = Paths.get(file);
        if (path.isAbsolute()) {
            return path;
        }
        // ⚠ 相对路径按**进程工作目录**解析，这是刻意的：容器挂载点与 CI 检出目录都不可预知，
        // 写死绝对路径反而更脆。代价是文件落点随启动方式漂移，因此
        // reportInitPasswordSource() 会无条件打印解析后的绝对路径。
        // 本仓库两种受支持的启动方式（scripts/start-all.py、scripts/dev-start-backend.py）
        // 都把 cwd 固定成仓库根 → 规范落点是 <仓库根>/.bootstrap-credentials。
        // 手工 `java -jar` 时必须自己保证 cwd，否则凭据会落到别处（历史踩坑：
        // 曾在 judge-auth/ 下留下一份孤儿凭据文件，而 isBootstrapPending() 查的是仓库根 → 静默为 false）。
        return Paths.get(System.getProperty("user.dir")).resolve(path);
    }
}