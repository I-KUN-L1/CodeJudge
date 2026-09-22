package com.codejudge.ai.security;

import com.codejudge.common.exceptions.ForbiddenException;
import com.codejudge.common.exceptions.UnauthorizedException;
import org.springframework.stereotype.Component;

/**
 * AI 服务的入口鉴权守卫（WebFlux 版）。
 *
 * <p>对应底座 {@code zx-aigc} 的 {@code RoleGuardWebFilter}。改成一个可在 Controller /
 * Service 内显式调用的组件，而不是 WebFilter，原因有二：
 * <ol>
 *   <li><b>可测</b>：Filter 只能通过完整的 HTTP 往返验证，而点评链路的权限判断需要与
 *       「归属校验（学员仅限自己的提交）」在同一处可读；</li>
 *   <li><b>不误伤</b>：Filter 按路径前缀拦截，容易把「明明允许学员访问」的
 *       {@code /ai/review/stream} 一并挡掉。点评接口对学员开放，只有知识库维护才限特权角色。</li>
 * </ol>
 *
 * <p>注意：本类只做「是否登录 / 是否特权角色」的粗粒度判断。
 * 「这个提交是不是你的」属于数据级归属校验，必须等拿到 {@code submission.userId}
 * 之后再做，见 {@code ReviewContextService#assemble}。
 */
@Component
public class AiAccessGuard {

    /** 要求已登录；未登录抛 401 */
    public AiIdentity requireLogin(AiIdentity identity) {
        if (identity == null || !identity.authenticated()) {
            throw new UnauthorizedException("请先登录后再使用 AI 点评");
        }
        return identity;
    }

    /** 要求特权角色（员工/教师）；学员或未登录一律拒绝 */
    public AiIdentity requirePrivileged(AiIdentity identity) {
        requireLogin(identity);
        if (!identity.privileged()) {
            throw new ForbiddenException("该操作仅限教师或管理员");
        }
        return identity;
    }
}
