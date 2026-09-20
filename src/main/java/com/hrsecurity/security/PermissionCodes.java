package com.hrsecurity.security;

/**
 * 权限编码常量：与 sys_permission.perm_code 一一对应，避免各处手写字符串写错。
 * 权限编码会作为 Spring Security 的 authority 放进认证信息，供
 * {@code @PreAuthorize("hasAuthority(...)")} 判断。
 */
public final class PermissionCodes {

    /** 查看员工敏感信息明文（身份证/银行卡/工资），仅 ADMIN 默认拥有 */
    public static final String EMPLOYEE_SENSITIVE_READ = "employee:sensitive:read";

    private PermissionCodes() {
    }
}
