package com.hrsecurity.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 登录成功后的返回体：token + 用户信息。
 */
@Data
@Builder
public class LoginResponse {

    /** 有效期 24 小时的 JWT，前端后续请求放入 Authorization: Bearer <token> */
    private String token;

    private UserInfoVO user;
}
