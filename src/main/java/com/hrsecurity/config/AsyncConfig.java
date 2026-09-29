package com.hrsecurity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池配置（第 7 周：审计日志异步落库）。
 *
 * 为什么必须自定义线程池，而不是直接用 @Async 默认行为：
 * Spring Boot 默认给 @Async 分配的是 SimpleAsyncTaskExecutor——**每次调用新建一个线程、不做复用也不限并发**，
 * 高并发下线程数会失控（"一个请求一个线程还带审计"，很容易把机器拖垮）。
 * 这里用有界队列的固定池，并且**拒绝策略选 CallerRunsPolicy**：
 * 队列满时让调用线程自己写库 —— 相当于自动降级成同步，宁可让接口慢一点，也不丢审计记录
 * （审计丢记录比慢一点严重得多；这条取舍写在 docs/audit-design.md）。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("auditExecutor")
    public ThreadPoolTaskExecutor auditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 审计是低耗时纯 insert：核心线程不需要多，给突发留一点弹性即可
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("audit-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅停机：让已入队的审计记录写完再退出，避免关停时丢审计
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
