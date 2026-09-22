package com.codejudge.ai.service.memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Redis 中持久化的单条对话消息（复用自底座 {@code zx-aigc}，原样保留）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RedisMessage {

    /** user / assistant */
    private String role;

    private String content;
}
