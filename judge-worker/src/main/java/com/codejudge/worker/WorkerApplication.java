package com.codejudge.worker;

import com.codejudge.worker.config.WorkerProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 判题机启动类（:9085，多实例 9085/9185/9285）。
 *
 * <p>职责边界：MQ 消费判题任务、沙箱执行、结果回写与 RESULT 事件发布、心跳上报。
 * 不对外提供业务 REST 接口 —— 判题任务的唯一入口是 MQ，避免旁路写入破坏单写者模型。
 */
@SpringBootApplication
@MapperScan("com.codejudge.worker.mapper")
@EnableFeignClients(basePackages = "com.codejudge.api.client")
@EnableScheduling
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(WorkerApplication.class).run(args);
    }
}
