package com.hrsecurity.security;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 登录用户的身份信息，认证成功后放入 SecurityContext。
 * roles 存角色编码（如 ADMIN/EMPLOYEE），过滤器据此生成 ROLE_xxx 权限，
 * 供 @PreAuthorize("hasRole('ADMIN')") 方法级鉴权使用。
 */
@Data
@AllArgsConstructor
public class LoginUser {

    private Long uid;
    private String username;
    private List<String> roles;
}
