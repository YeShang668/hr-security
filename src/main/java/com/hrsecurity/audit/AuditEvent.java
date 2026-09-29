package com.hrsecurity.audit;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次审计事件（切面在**请求线程里**组装完成后，交给异步线程落库）。
 *
 * 为什么要单独搞一个对象，而不是异步方法里现取用户信息（本周必踩的坑，见 docs/audit-design.md）：
 * 用户信息在 SecurityContextHolder、请求信息在 RequestContextHolder，两者都是 **ThreadLocal**；
 * 线程池里的异步线程拿不到它们（拿到的是 null）。所以必须在切面里先把
 * 用户/IP/UA/时间取出来放进这个对象，再传参给异步方法——
 * 这也解释了为什么这个类是不可变的"值对象"而不是去异步线程里读上下文。
 */
@Data
@Builder
public class AuditEvent {

    /** 操作者用户 id（内部任务/未登录场景为 null） */
    private Long userId;

    /** 操作者用户名（与 user_id 一起冗余落库，改名/删号后仍可追溯） */
    private String username;

    /** 操作类型，来自 {@link AuditLog#operation()} */
    private String operation;

    private String targetType;

    private String targetId;

    private String detail;

    /** SUCCESS / FAILURE */
    private String result;

    /** 客户端 IP（优先 X-Forwarded-For 首个地址） */
    private String ip;

    private String userAgent;

    /** 事件发生时间（在请求线程里取，不是异步线程插入的时间） */
    private LocalDateTime occurredAt;

    /** 方法耗时（毫秒），用于观察"审计是否拖慢接口" */
    private long elapsedMs;
}
