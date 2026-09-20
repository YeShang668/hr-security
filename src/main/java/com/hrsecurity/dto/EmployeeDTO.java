package com.hrsecurity.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 员工新增/修改请求参数。
 *
 * 第 6 周起：身份证/手机号/银行卡/工资都是**明文传入**，由后端加密后落库（TypeHandler），
 * 接口与前端始终不接触密文。校验放在入口，脏数据不进加密流程。
 *
 * 修改语义（重要）：敏感字段**留空 = 不修改**。原因有两个：
 * 1. 列表接口返回的是脱敏值（如 138****0001），若前端把脱敏值回填表单再提交，
 *    就会把 "138****0001" 当新明文加密写库，真实号码被覆盖丢失；
 * 2. 不强制每次修改都重新输入完整敏感信息，减少明文在链路上的暴露次数。
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

    /** 手机号明文（留空表示不修改；新增时可为空） */
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 身份证号明文（留空表示不修改；新增时可为空） */
    @Pattern(regexp = "^$|^[1-9]\\d{5}(18|19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx]$",
            message = "身份证号格式不正确")
    private String idCard;

    /** 银行卡号明文（留空表示不修改） */
    @Pattern(regexp = "^$|^\\d{16,19}$", message = "银行卡号应为 16~19 位数字")
    private String bankCard;

    /** 工资（留空表示不修改） */
    @DecimalMin(value = "0", message = "工资不能为负数")
    @Digits(integer = 8, fraction = 2, message = "工资最多 8 位整数、2 位小数")
    private BigDecimal salary;

    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱最长 100 个字符")
    private String email;

    @NotNull(message = "部门不能为空")
    private Long deptId;

    /** 入职日期 */
    private LocalDate entryDate;
}
