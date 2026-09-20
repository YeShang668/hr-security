package com.hrsecurity.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 员工敏感信息明文出参（仅 employee:sensitive:read 权限可调用）。
 *
 * 为什么单独一个接口、单独一个 VO，而不是给列表加个 canSeePlain 开关：
 * 1. 权限边界清晰——明文出口只有一个，审计（第 7 周 AOP）也只需盯这一处；
 * 2. 列表接口即使被越权读取，也天然只有脱敏值，泄露面小；
 * 3. 前端"查看完整信息"是明确的用户动作，对应一次显式的、可记录的访问行为。
 */
@Data
@Builder
public class EmployeeSensitiveVO {

    private Long id;

    private String empNo;

    private String name;

    /** 身份证明文 */
    private String idCard;

    /** 手机号明文 */
    private String phone;

    /** 银行卡明文 */
    private String bankCard;

    /** 工资明文 */
    private String salary;
}
