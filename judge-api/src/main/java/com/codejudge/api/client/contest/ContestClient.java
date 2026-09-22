package com.codejudge.api.client.contest;

import com.codejudge.api.dto.contest.ContestContextDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 竞赛服务客户端（内部契约）。
 *
 * <p>仅 judge-submission 在受理竞赛提交时调用（对应端点受 {@code InternalOnlyGuard} 保护，
 * 携带用户身份的外部请求一律 403）。
 *
 * <p>有意不配 fallback：竞赛校验的正确失败姿势是「拒绝受理」而不是「降级放行」——
 * 若竞赛服务不可用时放行提交，一旦这些人不是报名选手或竞赛已结束，
 * 就会往排行榜里写入脏数据（且无法自动回滚）。调用方捕获异常后返回 4xx 提示。
 */
@FeignClient(value = "judge-contest", contextId = "contestClient")
public interface ContestClient {

    /**
     * 拉取竞赛上下文。
     *
     * @param contestId 竞赛 id
     * @param userId    可选：传入则同时返回该用户是否已报名
     * @return 竞赛上下文；竞赛不存在时服务端抛业务异常
     */
    @GetMapping("/internal/contests/{id}/context")
    ContestContextDTO getContext(@PathVariable("id") Long contestId,
                                 @RequestParam(value = "userId", required = false) Long userId);
}
