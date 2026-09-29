package com.hrsecurity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志实体，对应 sys_audit_log。
 *
 * 字段设计回答五个问题（谁/何时/做了什么/对什么做的/来自哪）：
 * user_id + username（冗余）、created_at、operation、target_type + target_id、ip + user_agent。
 *
 * 两个刻意的设计：
 * 1. **created_at 不用 MyBatis-Plus 自动填充**：审计时间应该是"事件发生时间"（在请求线程取的），
 *    而不是异步线程实际插入的时间——并发下两者可能差出可观的量，审计必须反映真实时刻；
 * 2. **只增不改不删**：审计表没有 update/delete 入口，也不参与逻辑删除，
 *    写入后即成为证据（表增长问题见 docs/audit-design.md 的归档/分区演进）。
 */
@Data
@TableName("sys_audit_log")
public class SysAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作者用户 id；取不到（内部任务）时为 null */
    private Long userId;

    /** 操作者用户名（冗余存储） */
    private String username;

    /** 操作类型，如"查看员工敏感信息" */
    private String operation;

    /** 操作对象类型：EMPLOYEE / DEPT / USER / CRYPTO / KEY / AUDIT_LOG */
    private String targetType;

    /** 操作对象 id（字符串类型，兼容非数字主键） */
    private String targetId;

    /** 补充说明：禁止写入敏感明文 */
    private String detail;

    /** SUCCESS / FAILURE */
    private String result;

    /** 客户端 IP（X-Forwarded-For 首个地址优先） */
    private String ip;

    /** 客户端 User-Agent */
    private String userAgent;

    /** 事件发生时间 */
    private LocalDateTime createdAt;
}
