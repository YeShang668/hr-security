package com.hrsecurity.audit;

/**
 * 审计事件写入器（切面只依赖这个接口）：
 * 把"组装事件"和"落库方式"解耦——今天是异步写单表，将来换消息队列/ELK/独立审计库，
 * 切面与业务代码都不用动。
 */
public interface AuditLogRecorder {

    /** 记录一次审计事件（实现必须是异步/不阻塞的，见 AsyncAuditLogRecorder） */
    void record(AuditEvent event);
}
