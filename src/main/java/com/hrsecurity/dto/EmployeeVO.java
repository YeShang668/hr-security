package com.hrsecurity.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 员工出参 VO：员工信息 + 部门名（列表页直接展示，免去前端二次请求）。
 *
 * 敏感字段（手机号/身份证/银行卡/工资）**默认脱敏**，只有具备
 * employee:sensitive:read 权限的调用者才看到明文——脱敏判定在服务端做，
 * 前端拿不到明文也就无从泄露（前端脱敏等于没脱敏）。
 *
 * sensitiveVisible 告诉前端"当前账号有没有权利看明文"：有权限才显示"查看完整信息"按钮；
 * 但按钮只是体验优化，真正的拦截在后端敏感接口（无权限 403）。
 */
@Data
@Builder
public class EmployeeVO {

    private Long id;

    private String empNo;

    private String name;

    /** 1男 2女 */
    private Integer gender;

    /** 手机号（无权限时形如 138****0001） */
    private String phone;

    /** 身份证号（无权限时形如 110***********1234） */
    private String idCard;

    /** 银行卡号（无权限时仅保留后 4 位） */
    private String bankCard;

    /** 工资（无权限时为 ****） */
    private String salary;

    /** 当前调用者是否有权查看敏感明文（前端据此决定是否显示"查看完整信息"） */
    private Boolean sensitiveVisible;

    private String email;

    private Long deptId;

    /** 部门名（Service 层联查填充） */
    private String deptName;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate entryDate;

    /** 1在职 0离职 */
    private Integer status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
