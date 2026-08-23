package com.hrsecurity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 员工新增/修改请求参数。
 * 红线：身份证/工资等敏感字段 9 月 AES 加密周再加，本周不建列。
 */
@Data
public class EmployeeDTO {

    @NotBlank(message = "工号不能为空")
    @Size(max = 20, message = "工号最长 20 个字符")
    private String empNo;

    @NotBlank(message = "姓名不能为空")
    @Size(max = 50, message = "姓名最长 50 个字符")
    private String name;

    /** 1男 2女 */
    private Integer gender;

    /** 手机号（9 月加密，本周仅占位存储） */
    @Size(max = 20, message = "手机号最长 20 个字符")
    private String phone;

    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱最长 100 个字符")
    private String email;

    @NotNull(message = "部门不能为空")
    private Long deptId;

    /** 入职日期 */
    private LocalDate entryDate;
}
