package com.hrsecurity.audit;

import com.hrsecurity.common.IpUtils;
import com.hrsecurity.security.LoginUser;
import com.hrsecurity.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

/**
 * 审计切面：拦截带 {@link AuditLog} 的方法，把"谁、何时、做了什么、对什么做的、来自哪、结果如何"
 * 组装成 {@link AuditEvent}，交给异步写入器落库。
 *
 * 三个关键设计（都对应一份踩坑成本，详见 docs/audit-design.md）：
 *
 * 1. **@Order(HIGHEST_PRECEDENCE + 100) 放在事务外层**：Spring 事务通知的 order 是 LOWEST_PRECEDENCE（最内层），
 *    切面 order 取值比它小 = 比事务更靠外。于是执行顺序是
 *    「切面进入 → 开启事务 → 业务方法 → 提交事务 → 切面记录」，
 *    记录时事务已经真正提交，不会出现"事务回滚了、审计却说成功"。
 *    如果反过来（切面在事务内）并且审计是同步写库，还会有个更隐蔽的问题：
 *    审计 insert 沾在业务事务里，业务一失败，连"失败"这条审计记录自己也一起回滚了。
 *
 * 2. **在请求线程里先取上下文**：SecurityContextHolder / RequestContextHolder 都是 ThreadLocal，
 *    异步线程取不到（见 {@link AuditEvent}）。所以这里取完再传参。
 *
 * 3. **成功失败都记，且失败不影响业务**：记录本身出错只写日志，绝不让审计把正常业务请求带崩；
 *    异常仍然原样抛出（审计是旁路，不改变业务语义）。
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@RequiredArgsConstructor
public class AuditLogAspect {

    /** detail 是 VARCHAR(500)，留点余量避免中文截断报错 */
    private static final int DETAIL_MAX = 480;

    private final AuditLogRecorder recorder;

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {
        // ① 请求线程内取上下文：用户 + 请求（IP/UA）+ 事件时间
        LoginUser user = SecurityUtils.currentUser();
        HttpServletRequest request = currentRequest();
        String ip = IpUtils.clientIp(request);
        String userAgent = request == null ? null : request.getHeader("User-Agent");
        LocalDateTime occurredAt = LocalDateTime.now();
        long start = System.nanoTime();

        try {
            Object result = pjp.proceed();
            // ② 成功：记录（方法真的返回了才记 SUCCESS）
            record(user, ip, userAgent, occurredAt, start, auditLog,
                    AuditResult.SUCCESS, resolveTargetId(auditLog, pjp.getArgs(), result), null);
            return result;
        } catch (Throwable t) {
            // ③ 失败：同样记一笔（越权/试探/运维失败都是审计关心的），然后原样抛出
            record(user, ip, userAgent, occurredAt, start, auditLog,
                    AuditResult.FAILURE, resolveTargetId(auditLog, pjp.getArgs(), null), t.getMessage());
            throw t;
        } finally {
            // ④ 兜底清理线程内的补充说明（线程池复用会串味，见 AuditTrace）
            AuditTrace.clear();
        }
    }

    private void record(LoginUser user, String ip, String userAgent, LocalDateTime occurredAt, long startNanos,
                        AuditLog auditLog, AuditResult result, String targetId, String failureMessage) {
        try {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            AuditEvent event = AuditEvent.builder()
                    .userId(user == null ? null : user.getUid())
                    .username(user == null ? null : user.getUsername())
                    .operation(auditLog.operation())
                    .targetType(blankToNull(auditLog.targetType()))
                    .targetId(targetId)
                    .detail(buildDetail(auditLog, elapsedMs, failureMessage))
                    .result(result.name())
                    .ip(ip)
                    .userAgent(truncate(userAgent, 255))
                    .occurredAt(occurredAt)
                    .elapsedMs(elapsedMs)
                    .build();
            recorder.record(event);
        } catch (Exception e) {
            // 审计是旁路：组装/投递失败只告警，不能影响业务结果
            log.error("审计事件组装失败（operation={}）", auditLog.operation(), e);
        }
    }

    /**
     * detail 由三部分组成：注解里的静态说明 + 业务方法写入的运行时补充（{@link AuditTrace}）
     * + 耗时，失败时附上原因。
     * 红线：只写"看过/改过什么"，绝不拼接被访问的敏感明文。
     */
    private String buildDetail(AuditLog auditLog, long elapsedMs, String failureMessage) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(auditLog.detail())) {
            sb.append(auditLog.detail());
        }
        String runtimeDetail = AuditTrace.drain();
        if (StringUtils.hasText(runtimeDetail)) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(runtimeDetail);
        }
        if (sb.length() > 0) {
            sb.append("；");
        }
        sb.append("耗时 ").append(elapsedMs).append("ms");
        if (failureMessage != null) {
            sb.append("；失败原因：").append(failureMessage);
        }
        return truncate(sb.toString(), DETAIL_MAX);
    }

    /**
     * 目标 id：优先取注解指定的入参（如 getSensitive(Long id) 的 id）；
     * 入参不是 id 时（如 create(EmployeeDTO)）退而从返回值里找 getId()——
     * 这样"新增员工"也能记下"新增出来的那条记录 id"，不用为埋点改业务方法签名。
     */
    private String resolveTargetId(AuditLog auditLog, Object[] args, Object result) {
        int index = auditLog.targetIdArgIndex();
        if (index >= 0 && args != null && index < args.length && args[index] != null) {
            return String.valueOf(args[index]);
        }
        return idFromResult(result);
    }

    private String idFromResult(Object result) {
        if (result == null) {
            return null;
        }
        try {
            Method getId = result.getClass().getMethod("getId");
            Object id = getId.invoke(result);
            return id == null ? null : String.valueOf(id);
        } catch (ReflectiveOperationException e) {
            // 返回值没有 id（如 void、BackfillResult）：目标 id 留空即可，不影响其余字段
            return null;
        }
    }

    /** 请求可能不存在（内部定时任务调用）：此时 IP/UA 记为 null 而不是直接报错 */
    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
