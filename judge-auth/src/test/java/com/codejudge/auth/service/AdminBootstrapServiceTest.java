package com.codejudge.auth.service;

import com.codejudge.api.client.user.UserClient;
import com.codejudge.api.dto.user.BootstrapAdminDTO;
import com.codejudge.api.dto.user.PasswordChangeDTO;
import com.codejudge.common.exceptions.BadRequestException;
import feign.codec.DecodeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 首个管理员安全引导测试 —— 覆盖需求「初次运行生成一个文件写入初始账密，改密后消失」的完整闭环。
 *
 * <p>为什么必须有这个测试：这条闭环的<b>两端都只在特殊时机发生</b>（全新部署的第一次启动、
 * 以及那之后的第一次改密），日常跑服务永远碰不到。仅靠端到端脚本无法覆盖 ——
 * 本机库里早就存在管理员，所以 {@code bootstrapIfNeeded()} 每次都直接 return，
 * 真机探针只能观察到"跳过"，无法证明"生成"与"删除"确实可用。
 * 用临时目录 + mock 掉远程调用后，这两个分支可以稳定复现。
 *
 * <p>同时钉住一个安全姿态：<b>校验失败绝不删除凭据文件</b>（fail-closed）。
 * 反过来的实现（先删文件再改密）会在改密失败时把唯一的口令记录弄丢，
 * 管理员将永久失去入口 —— 这个方向必须由测试守住。
 */
@ExtendWith(MockitoExtension.class)
class AdminBootstrapServiceTest {

    private static final String PHONE = "13800000000";
    private static final String FILE_NAME = ".bootstrap-credentials";

    @Mock
    private UserClient userClient;

    @TempDir
    Path tempDir;

    private Path credentialFile;

    @BeforeEach
    void setUp() {
        credentialFile = tempDir.resolve(FILE_NAME);
    }

    private AdminBootstrapService service(String configuredPassword) {
        return new AdminBootstrapService(userClient, credentialFile.toString(),
                PHONE, "admin", configuredPassword);
    }

    private String readCredentialFile() throws IOException {
        return Files.readString(credentialFile, StandardCharsets.UTF_8);
    }

    /* ==================== 首次运行：生成 ==================== */

    @Test
    void firstRunCreatesAdminAndWritesCredentialFile() throws IOException {
        when(userClient.adminExists()).thenReturn(false);
        AdminBootstrapService svc = service("");

        assertFalse(svc.isBootstrapPending(), "引导前不应有凭据文件");
        svc.bootstrapIfNeeded();

        ArgumentCaptor<BootstrapAdminDTO> captor = ArgumentCaptor.forClass(BootstrapAdminDTO.class);
        verify(userClient).createBootstrapAdmin(captor.capture());
        BootstrapAdminDTO sent = captor.getValue();
        assertEquals(PHONE, sent.getCellPhone());
        assertEquals("admin", sent.getUsername());

        assertTrue(Files.exists(credentialFile), "首次运行必须生成凭据文件");
        assertTrue(svc.isBootstrapPending(), "生成后 isBootstrapPending 必须为真（前端据此提示改密）");

        String content = readCredentialFile();
        assertTrue(content.contains(PHONE), "凭据文件应写明账号");
        assertTrue(content.contains(sent.getPassword()), "凭据文件应写明初始口令");
        assertTrue(content.contains("CodeJudge"), "凭据文件抬头应为 CodeJudge");
        assertFalse(content.contains("知行智学"), "不应残留 zx-learn 品牌文案");
    }

    @Test
    void generatedPasswordIsStrongAndNotAWeakDefault() {
        when(userClient.adminExists()).thenReturn(false);
        service("").bootstrapIfNeeded();

        ArgumentCaptor<BootstrapAdminDTO> captor = ArgumentCaptor.forClass(BootstrapAdminDTO.class);
        verify(userClient).createBootstrapAdmin(captor.capture());
        String pwd = captor.getValue().getPassword();

        assertNotEquals("123456", pwd, "绝不能再出现硬编码兜底弱口令");
        assertTrue(pwd.length() >= 24, "随机初始口令长度应 ≥ 24，实际=" + pwd.length());
        assertTrue(pwd.startsWith("Cj_"), "随机口令带可识别前缀，便于运维确认来源");
    }

    @Test
    void twoRunsGenerateDifferentPasswords() {
        when(userClient.adminExists()).thenReturn(false);
        service("").bootstrapIfNeeded();

        // 第二次引导：换个空目录（模拟另一台全新部署）再跑一遍
        Path otherDir = tempDir.resolve("second");
        assertTrue(otherDir.toFile().mkdirs(), "临时子目录应可创建");
        new AdminBootstrapService(userClient, otherDir.resolve(FILE_NAME).toString(),
                PHONE, "admin", "").bootstrapIfNeeded();

        ArgumentCaptor<BootstrapAdminDTO> captor = ArgumentCaptor.forClass(BootstrapAdminDTO.class);
        verify(userClient, times(2)).createBootstrapAdmin(captor.capture());
        List<BootstrapAdminDTO> sent = captor.getAllValues();

        assertNotEquals(sent.get(0).getPassword(), sent.get(1).getPassword(),
                "两次引导必须生成不同口令（CSPRNG 而非常量）");
        assertTrue(otherDir.resolve(FILE_NAME).toFile().exists(), "第二次引导也应落盘凭据");
    }

    @Test
    void secondRunOnSameDirSkipsInsteadOfOverwriting() throws IOException {
        // 幂等性：凭据文件已在 → 再启动一次不得重新生成、不得覆盖文件内容
        when(userClient.adminExists()).thenReturn(false);
        service("").bootstrapIfNeeded();
        String afterFirst = readCredentialFile();

        service("").bootstrapIfNeeded();

        verify(userClient, times(1)).createBootstrapAdmin(any());
        assertEquals(afterFirst, readCredentialFile(),
                "凭据文件不得被覆盖 —— 否则运维第一次读到的口令会失效");
    }

    @Test
    void configuredPasswordIsUsedVerbatim() throws IOException {
        when(userClient.adminExists()).thenReturn(false);
        String configured = "Cj-Configured-Password-For-Test";
        service(configured).bootstrapIfNeeded();

        ArgumentCaptor<BootstrapAdminDTO> captor = ArgumentCaptor.forClass(BootstrapAdminDTO.class);
        verify(userClient).createBootstrapAdmin(captor.capture());
        assertEquals(configured, captor.getValue().getPassword());
        assertTrue(readCredentialFile().contains(configured));
    }

    /* ==================== 跳过条件 ==================== */

    @Test
    void existingAdminSkipsBootstrapAndWritesNothing() {
        when(userClient.adminExists()).thenReturn(true);
        AdminBootstrapService svc = service("Cj-something-long-enough");

        svc.bootstrapIfNeeded();

        verify(userClient, never()).createBootstrapAdmin(any());
        assertFalse(Files.exists(credentialFile), "已有管理员时不应生成凭据文件");
        assertFalse(svc.isBootstrapPending());
    }

    @Test
    void existingCredentialFileSkipsEvenBeforeAskingRemote() {
        // 凭据文件在 → 说明引导已发生过，连"有没有管理员"都不必问（省一次跨服务调用）
        assertDoesNotThrow(() -> Files.writeString(credentialFile, "sentinel", StandardCharsets.UTF_8));
        service("Cj-something-long-enough").bootstrapIfNeeded();

        verifyNoInteractions(userClient);
        assertTrue(service("").isBootstrapPending(), "既有凭据文件必须仍被判为待改密");
    }

    /* ==================== 改密：文件必须消失 ==================== */

    @Test
    void successfulPasswordChangeDeletesCredentialFile() throws IOException {
        Files.writeString(credentialFile, "== CodeJudge ==\n初始密码：Cj_whatever\n", StandardCharsets.UTF_8);
        AdminBootstrapService svc = service("Cj-something-long-enough");
        assertTrue(svc.isBootstrapPending());

        svc.changeBootstrapPassword(PHONE, "Cj_whatever", "Cj-New-Password-For-Test");

        ArgumentCaptor<PasswordChangeDTO> captor = ArgumentCaptor.forClass(PasswordChangeDTO.class);
        verify(userClient).changeBootstrapPassword(captor.capture());
        assertEquals("Cj_whatever", captor.getValue().getOldPassword());
        assertEquals("Cj-New-Password-For-Test", captor.getValue().getNewPassword());

        assertFalse(Files.exists(credentialFile), "改密成功后凭据文件必须被删除（需求：改密后消失）");
        assertFalse(svc.isBootstrapPending(), "改密完成后不应再提示改密");
    }

    @Test
    void failedPasswordChangeKeepsCredentialFile() throws IOException {
        Files.writeString(credentialFile, "== CodeJudge ==\n", StandardCharsets.UTF_8);
        doThrow(new BadRequestException("原密码错误"))
                .when(userClient).changeBootstrapPassword(any());

        AdminBootstrapService svc = service("Cj-something-long-enough");
        assertThrows(BadRequestException.class,
                () -> svc.changeBootstrapPassword(PHONE, "wrong-old", "Cj-New-Password-For-Test"));

        assertTrue(Files.exists(credentialFile),
                "校验失败必须保留凭据文件（fail-closed）——先删后改会永久丢失唯一口令记录");
        assertTrue(svc.isBootstrapPending());
    }

    @Test
    void feignDecodeExceptionIsUnwrappedToBusinessException() throws IOException {
        // feign 会把 Decoder 抛出的业务异常包成 DecodeException；若不还原，
        // "原密码错误" 会退化成 500，前端拿不到可读提示。
        Files.writeString(credentialFile, "== CodeJudge ==\n", StandardCharsets.UTF_8);
        DecodeException wrapped = mock(DecodeException.class);
        when(wrapped.getCause()).thenReturn(new BadRequestException("原密码错误"));
        doThrow(wrapped).when(userClient).changeBootstrapPassword(any());

        AdminBootstrapService svc = service("Cj-something-long-enough");
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> svc.changeBootstrapPassword(PHONE, "wrong-old", "Cj-New-Password-For-Test"));
        assertEquals("原密码错误", e.getMessage());
        assertTrue(Files.exists(credentialFile), "解包后仍是失败路径，凭据文件必须保留");
    }

    @Test
    void deleteIsIdempotentWhenFileAlreadyGone() {
        // 文件不存在时改密成功也不应抛异常（并发或人工删过的场景）
        AdminBootstrapService svc = service("Cj-something-long-enough");
        assertDoesNotThrow(() -> svc.changeBootstrapPassword(PHONE, "old", "new"));
        assertFalse(Files.exists(credentialFile));
    }
}
