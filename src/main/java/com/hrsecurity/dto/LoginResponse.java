package com.hrsecurity.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 登录成功后的返回体：token + 用户信息（含角色）。
 */
@Data
@Builder
public class LoginResponse {

    /** 有效期 24 小时的 JWT，前端后续请求放入 Authorization: Bearer <token> */
    private String token;

    private UserInfoVO user;

    /** 角色编码列表，冗余返回便于前端直接使用（token 里也已包含） */
    private List<String> roles;
}
