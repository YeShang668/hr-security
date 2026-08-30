package com.hrsecurity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Redis 登录会话：token 校验通过后以此为"登录态有效"的依据，
 * 只存身份（uid/username），角色不存会话，每次从角色缓存/数据库取最新值，
 * 保证角色变更即时生效。存进 Redis 需要无参构造 + getter/setter。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginSession {
    private Long uid;
    private String username;
}
