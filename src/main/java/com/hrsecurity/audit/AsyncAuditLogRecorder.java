package com.hrsecurity.audit;

import com.hrsecurity.entity.SysAuditLog;
import com.hrsecurity.mapper.SysAuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 审计事件异步写入（@Async("auditExecutor")）。
 *
 * 为什么必须异步：审计是"旁路需求"，不该让用户查看敏感信息的响应多等一次数据库写；
 * 顺带一个安全收益——写库耗时被移出请求线程，接口响应时间不随审计表增长而变差
 * （审计表是全库增长最快的表之一，这条很实际）。
 *
 * 三个坑（见 docs/audit-design.md）：
 * 1. 同类内部调用不走代理：本方法必须由**别的 Bean**（切面）通过接口调用，否则不会异步；
 * 2. 线程池要自定义：默认 SimpleAsyncTaskExecutor 每次新建线程且不设上限，高并发下会把线程耗尽，
 *    所以用 AsyncConfig 里的 auditExecutor（有界队列 + 拒绝时由调用线程兜底执行）；
 * 3. ThreadLocal 不跨线程：这里只用事件对象里的值，绝不在这里读 SecurityContext/RequestContext。
 *
 * @Async 方法里抛异常不会影响调用方（异常只会在异步线程里冒出去），这里仍显式 try-catch：
 * 审计写失败要留下清晰告警，且绝不能因为一个审计写失败把线程池任务搞脏。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncAuditLogRecorder implements AuditLogRecorder {

    private final SysAuditLogMapper auditLogMapper;

    @Async("auditExecutor")
    @Override
    public void record(AuditEvent event) {
        try {
            // 打开 DEBUG 可看到写入线程名（audit-N）：这是"确实异步了"的直接证据——
            // 若这里打印的是 http-nio-8080-exec-*，说明 @Async 没生效（多半是同类自调用）
            log.debug("审计异步写入：operation={}, thread={}", event.getOperation(), Thread.currentThread().getName());
            SysAuditLog entity = new SysAuditLog();
            entity.setUserId(event.getUserId());
            entity.setUsername(event.getUsername());
            entity.setOperation(event.getOperation());
            entity.setTargetType(event.getTargetType());
            entity.setTargetId(event.getTargetId());
            entity.setDetail(event.getDetail());
            entity.setResult(event.getResult());
            entity.setIp(event.getIp());
            entity.setUserAgent(event.getUserAgent());
            // 用事件发生时间，而不是异步插入时间：并发下插入顺序可能乱，但审计必须反映真实时刻
            entity.setCreatedAt(event.getOccurredAt());
            auditLogMapper.insert(entity);
        } catch (Exception e) {
            log.error("审计日志写入失败（operation={}, targetId={}, user={}）",
                    event.getOperation(), event.getTargetId(), event.getUsername(), e);
        }
    }
}
