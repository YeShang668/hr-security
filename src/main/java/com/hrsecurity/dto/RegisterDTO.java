package com.hrsecurity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求参数。@NotBlank/@Size 由 spring-boot-starter-validation 校验，
 * 校验失败会走 GlobalExceptionHandler 返回统一格式。
 */
@Data
public class RegisterDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 20, message = "用户名长度需在 3-20 个字符之间")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字、下划线")
    private String username;

    /**
     * 口令强度（第 8 周加固）：8-32 位，且必须同时含字母与数字。
     *
     * 为什么是"长度 + 字符种类"而不是"必须含大小写和符号"：后者的边际收益低、误伤大，
     * 还会诱导用户用 P@ssw0rd1 这类可预测变形；先卡住最常见的一类（短口令、纯数字、纯字母）
     * 收益最高。更完整的做法是接"弱口令字典 + 已泄露口令库"（本项目不做，见 docs/security-hardening.md 已知不覆盖项）。
     *
     * 注意这里只约束**注册入口**：真实系统里改密/重置/管理员建号都是同一套规则，
     * 生产应抽成自定义校验注解或校验服务，而不是把正则散落到各个 DTO。
     */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 32, message = "密码长度需在 8-32 个字符之间")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "密码必须同时包含字母和数字")
    private String password;

    @Size(max = 50, message = "昵称最长 50 个字符")
    private String nickname;
}
