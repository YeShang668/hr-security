package com.hrsecurity.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志出参。
 * 只读视图：没有"更新时间"之类的字段，也没有任何密钥/密码材料。
 */
@Data
@Builder
public class AuditLogVO {

    private Long id;

    private Long userId;

    private String username;

    private String operation;

    private String targetType;

    private String targetId;

    private String detail;

    /** SUCCESS / FAILURE */
    private String result;

    private String ip;

    private String userAgent;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
