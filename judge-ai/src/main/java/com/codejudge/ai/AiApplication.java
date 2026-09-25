package com.codejudge.ai;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * AI 服务启动类（:9087）。
 *
 * <p>职责边界：
 * <ul>
 *   <li><b>点评生成</b>：把「题目 + 判题结论 + 用户代码 + RAG 片段」组装成 Prompt，经 LLM 生成结构化点评；</li>
 *   <li><b>向量检索</b>：pgvector 上的 {@code knowledge_chunk}（题目知识）与 {@code ai_review}
 *       （历史点评）两路召回，为 Prompt 注入可溯源的上下文；</li>
 *   <li><b>流式推送</b>：SSE 逐字返回点评增量（START / RETRIEVAL / DELTA / SOURCES / ERROR / END）。</li>
 * </ul>
 * <p>不执行判题、不写 MySQL：判题上下文经 judge-submission 的内部契约拉取，
 * 代码与用例输出在本服务内**只读不落库**（落库的是点评本身，写 PG）。
 *
 * <p>本服务跑在响应式栈（Netty）上，原因与代价见 pom.xml 顶部注释。
 * 由此带来两条硬约束：
 * <ol>
 *   <li>{@code com.codejudge.common.utils.UserContext} 是 ThreadLocal + Servlet 拦截器填充的，
 *       在 WebFlux 下**永远为空**（UserInfoInterceptor 是 Servlet-only）。
 *       因此本服务一律从请求头 {@code user-info} / {@code role-info} 直接取值，
 *       绝不调用 {@code UserContext.getXxx()} / {@code InternalOnlyGuard.checkInternal()}
 *       —— 那两个在本服务里恒等于「内部调用」，会静默放行一切越权请求。</li>
 *   <li>所有阻塞 IO（JDBC / Redis / Feign）都必须在 {@code Schedulers.boundedElastic()} 上执行，
 *       否则会阻塞 Netty 事件循环。本服务的做法是在 Flux 装配阶段统一
 *       {@code subscribeOn(boundedElastic)}，见 {@code ReviewService}。</li>
 * </ol>
 */
@SpringBootApplication
@EnableFeignClients(basePackages = "com.codejudge.api.client")
public class AiApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(AiApplication.class).run(args);
    }
}
