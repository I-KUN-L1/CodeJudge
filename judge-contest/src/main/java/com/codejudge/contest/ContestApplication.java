package com.codejudge.contest;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 竞赛服务启动类（:9086）。
 *
 * <p>职责边界：
 * <ul>
 *   <li><b>竞赛域</b>：竞赛生命周期（未开始/进行中/已结束/封榜）、题目编排、报名；</li>
 *   <li><b>排行榜</b>：Redis ZSet 实时榜（唯一写者），封榜冻结与快照留档；</li>
 *   <li><b>推送</b>：WebSocket 榜单订阅（对外冻结榜 / 对内实时榜）。</li>
 * </ul>
 * <p>不执行判题、不解析代码：判题结论只经 MQ 的 RESULT 事件进入本服务。
 * {@code @EnableScheduling} 供生命周期扫描与推送合并（Pusher）使用。
 */
@SpringBootApplication
@MapperScan("com.codejudge.contest.mapper")
@EnableFeignClients(basePackages = "com.codejudge.api.client")
@EnableScheduling
public class ContestApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(ContestApplication.class).run(args);
    }
}
