package com.codejudge.worker.mq;

import com.codejudge.worker.config.WorkerProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * worker 身份：workerId = 主机名:端口，跨重启稳定；
 * 在跑任务计数供心跳负载上报。
 */
@Component
public class WorkerIdentity {

    private final String workerId;

    /** 正在执行的判题任务数（心跳上报到 judge:worker:load ZSet） */
    private final AtomicInteger runningTasks = new AtomicInteger();

    public WorkerIdentity(@Value("${server.port:9085}") int port) {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        this.workerId = host + ":" + port;
    }

    public String workerId() {
        return workerId;
    }

    public void taskStarted() {
        runningTasks.incrementAndGet();
    }

    public void taskFinished() {
        runningTasks.decrementAndGet();
    }

    public int runningTasks() {
        return runningTasks.get();
    }
}
