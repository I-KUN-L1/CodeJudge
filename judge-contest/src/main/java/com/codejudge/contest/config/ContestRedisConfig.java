package com.codejudge.contest.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * 竞赛域 Redis 脚本装配。
 *
 * <p>榜单更新必须在 Redis 服务端原子完成（理由见 {@code lua/contest_rank_update.lua} 顶部注释），
 * 因此以 Lua 脚本的形式下发而不是用 {@code MULTI/EXEC}：脚本内需要**读中间结果再决定后续写**，
 * 这是 Redis 事务（WATCH/MULTI，只能盲写）做不到的。
 */
@Configuration
public class ContestRedisConfig {

    @Bean
    public RedisScript<List> contestRankScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/contest_rank_update.lua"));
        script.setResultType(List.class);
        return script;
    }
}
