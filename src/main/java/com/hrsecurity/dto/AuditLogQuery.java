package com.hrsecurity.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 审计日志查询条件（GET /api/audit-logs 的查询参数，由 Spring 按名字绑定）。
 *
 * 过滤维度对应审计的三个典型问题：
 * - "某人做过什么" → userId / username
 * - "这类操作都有谁做过" → operation / targetType
 * - "某个时间段发生了什么" → startTime / endTime（失败与成功都要能分开放）→ result
 *
 * 时间格式用 yyyy-MM-dd HH:mm:ss，与全局 Jackson 配置的序列化格式保持一致，
 * 避免"查询时用一套格式、返回时用另一套"的坑（第 5 周 BUG5-3 同源问题）。
 */
@Data
public class AuditLogQuery {

    private long page = 1;

    private long size = 10;

    private Long userId;

    /** 模糊匹配操作者用户名 */
    private String username;

    /** 模糊匹配操作类型（如"敏感"能筛出所有敏感信息相关操作） */
    private String operation;

    /** 精确匹配对象类型：EMPLOYEE / USER / CRYPTO / KEY */
    private String targetType;

    /** 精确匹配结果：SUCCESS / FAILURE */
    private String result;

    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}
