package com.codejudge.common.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.LayoutBase;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * stdout JSON 日志布局（上线检查清单第十轮 T10）。
 *
 * <h3>为什么本类在 judge-gateway 有一份拷贝</h3>
 * gateway 不依赖 judge-common（设计约束，见其 pom），而 prod profile 的
 * logback-spring.xml 引用本类 —— 类不在 classpath 上时 logback 解析配置失败、
 * 整体回退 Spring 默认格式。故按 judge-gateway/src/main/resources/logback-spring.xml
 * 的同步纪律维护这份拷贝：与 judge-common 同名类 + 两份 logback-spring.xml
 * 共三处必须一致，改动同步。
 *
 * <h3>为什么不用 logstash-logback-encoder</h3>
 * 该依赖不在本地仓库且外部源（ghcr/mirror）在本机被网络策略拦截，为日志格式
 * 引入一个拉不下来的包得不偿失。Spring Boot 自带的 Jackson 转义是完整可靠的
 * （消息里的引号 / 换行 / 中文都会被正确序列化），手写 pattern 的
 * {@code %replace} 方案做不到——它只处理已知字符，遇到消息内嵌 JSON 会产出
 * 非法 JSON 行，Loki 的 {@code | json} 解析会整行失败，比人读格式更糟。
 *
 * <h3>字段契约（Loki 查询依赖，改前先同步 RUNBOOK/看板）</h3>
 * {@code ts / level / application / logger / thread / requestId / message / stackTrace}
 * 其中 {@code requestId} 来自 MDC（{@link com.codejudge.common.interceptor.RequestIdInterceptor}
 * 写入，键 = {@code requestId}），无请求上下文的后台线程（MQ 消费、心跳）该字段缺失。
 * Loki 侧全链路查询：{@code {application="judge-submission"} | json | requestId="..."}。
 *
 * <h3>线程安全</h3>
 * Jackson {@link ObjectMapper} 是线程安全的（配置完成后不可变），logback 单
 * appender 串行调用 {@link #doLayout}，多 worker 消费线程也无竞态。
 */
public class JsonLogLayout extends LayoutBase<ILoggingEvent> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String doLayout(ILoggingEvent event) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("ts", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        doc.put("level", event.getLevel().toString());
        doc.put("application", applicationName());
        doc.put("logger", event.getLoggerName());
        doc.put("thread", event.getThreadName());
        String requestId = event.getMDCPropertyMap().get("requestId");
        if (requestId != null && !requestId.isBlank()) {
            doc.put("requestId", requestId);
        }
        doc.put("message", event.getFormattedMessage());
        String stack = stackTraceOf(event);
        if (stack != null) {
            doc.put("stackTrace", stack);
        }
        try {
            return MAPPER.writeValueAsString(doc) + "\n";
        } catch (Exception e) {
            // JSON 化失败必须留痕且不能丢事件本身：降级为单行转义文本
            return "{\"level\":\"ERROR\",\"application\":\"" + safe(event.getLoggerContextVO().getName())
                    + "\",\"message\":\"log layout failure: " + safe(e.toString())
                    + " origin=" + safe(event.getFormattedMessage()) + "\"}\n";
        }
    }

    private String stackTraceOf(ILoggingEvent event) {
        IThrowableProxy t = event.getThrowableProxy();
        if (t == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(t.getClassName()).append(": ").append(t.getMessage());
        StackTraceElementProxy[] frames = t.getStackTraceElementProxyArray();
        int limit = Math.min(frames == null ? 0 : frames.length, 50);
        for (int i = 0; i < limit; i++) {
            sb.append("\n\tat ").append(frames[i].getStackTraceElement());
        }
        if (frames != null && frames.length > limit) {
            sb.append("\n\t... ").append(frames.length - limit).append(" more");
        }
        return sb.toString();
    }

    /**
     * 应用名解析：优先 logback-spring.xml 的 springProperty(APP_NAME)，
     * 兜底 LoggerContext 名。Context 名默认是 "default"，拿它当 application
     * 会让 Loki 按 application 分流的查询全部落空 —— Prometheus 的
     * application 标签来自 spring.application.name，两边必须对齐。
     */
    private String applicationName() {
        ch.qos.logback.core.Context ctx = getContext();
        String name = ctx == null ? null : ctx.getProperty("APP_NAME");
        return (name == null || name.isBlank()) ? "unknown" : name;
    }

    /** 降级路径的最低限度转义（正常路径由 Jackson 负责，不走这里） */
    private String safe(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
