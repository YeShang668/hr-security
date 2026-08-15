package com.hrsecurity.security;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 登录用户的身份信息，认证成功后放入 SecurityContext，
 * 后续 RBAC 扩展时在这里追加权限集合即可。
 */
@Data
@AllArgsConstructor
public class LoginUser {

    private Long uid;
    private String username;
}
