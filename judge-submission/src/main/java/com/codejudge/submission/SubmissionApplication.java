package com.codejudge.submission;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 提交服务启动类（:9084）。
 *
 * <p>职责边界：受理提交 + 幂等 + MQ 投递 + 判题任务生命周期调度（故障转移/重试/死信）。
 * 不执行判题 —— 沙箱执行在 judge-worker。
 */
@SpringBootApplication
@MapperScan("com.codejudge.submission.mapper")
@EnableFeignClients(basePackages = "com.codejudge.api.client")
@EnableScheduling
public class SubmissionApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(SubmissionApplication.class).run(args);
    }
}
