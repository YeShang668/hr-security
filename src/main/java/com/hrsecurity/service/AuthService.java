package com.hrsecurity.service;

import com.hrsecurity.dto.LoginDTO;
import com.hrsecurity.dto.LoginResponse;
import com.hrsecurity.dto.RegisterDTO;
import com.hrsecurity.dto.UserInfoVO;

public interface AuthService {

    /** 注册：用户名唯一校验 + BCrypt 加密后落库 */
    void register(RegisterDTO dto);

    /** 登录：校验密码，成功返回 JWT */
    LoginResponse login(LoginDTO dto);

    /** 根据用户 id 返回用户信息（用于 /me） */
    UserInfoVO getUserInfo(Long userId);

    /** 登出：删除 Redis 会话与角色缓存，旧 token 立即失效 */
    void logout(String token);
}
