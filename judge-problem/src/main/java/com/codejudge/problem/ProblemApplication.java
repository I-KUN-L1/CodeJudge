package com.codejudge.problem;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * 题目服务启动类
 */
@SpringBootApplication
@MapperScan("com.codejudge.problem.mapper")
public class ProblemApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(ProblemApplication.class).run(args);
    }
}
