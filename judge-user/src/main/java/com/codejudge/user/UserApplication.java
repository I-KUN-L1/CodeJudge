package com.codejudge.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 用户服务启动类
 *
 * <p>{@code @EnableFeignClients}：启用 judge-api 里的服务间客户端（当前用于改密成功后
 * 回调 judge-auth 删除初始凭据文件，见 UserService.changePassword）。
 * 与 judge-worker / judge-contest 等服务的启用方式保持一致。</p>
 */
@SpringBootApplication
@MapperScan("com.codejudge.user.mapper")
@EnableFeignClients(basePackages = "com.codejudge.api.client")
public class UserApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(UserApplication.class).run(args);
    }
}
