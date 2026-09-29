package com.hrsecurity.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计埋点注解：打在"需要追责"的方法上，由 {@link AuditLogAspect} 统一记录"谁在什么时候做了什么"。
 *
 * 为什么用注解 + AOP 而不是在每个方法里手写 insert（面试点）：
 * 1. 不侵入业务：Service 只多一行注解，审计逻辑集中在一处，改字段/改存储不用翻业务代码；
 * 2. 不遗漏：敏感动作是"必须留痕"的清单，注解让清单在代码里一眼可见（grep 一下就能审阅全量埋点）；
 * 3. 一致：操作者、IP、UA、耗时的取法只有一份实现。
 *
 * 埋点红线（第 7 周）：明文查看、权限变更、密钥与数据级运维动作必须埋；
 * 注解只声明"做了什么"，**detail 绝不允许拼接身份证/手机号等敏感明文**——
 * 否则审计表自己会变成一张明文敏感数据表。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {

    /** 操作类型，如"查看员工敏感信息"。会写入 sys_audit_log.operation，检索时按它过滤 */
    String operation();

    /** 操作对象类型：EMPLOYEE / DEPT / USER / CRYPTO / KEY / AUDIT_LOG …… */
    String targetType() default "";

    /**
     * 目标 id 取第几个方法参数（从 0 开始；-1 = 不取参数）。
     * 例：getSensitive(Long id) → 0；updateStatus(Long id, ...) → 0。
     * 参数不是 id 时（如 create(EmployeeDTO)）留 -1，切面会退而从"方法返回值"里取 id 字段。
     */
    int targetIdArgIndex() default -1;

    /** 静态补充说明（比如"密文列不参与；明文出口唯一"），会拼进 detail */
    String detail() default "";
}
