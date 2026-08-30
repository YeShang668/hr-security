package com.hrsecurity.service;

import com.hrsecurity.dto.LoginSession;

/**
 * Redis 登录会话管理：login:token:{token}。
 * 会话 = 登录态凭证，token 验签通过后还必须会话存在才算有效，
 * 登出/服务端删除会话即可让旧 token 立即失效。
 */
public interface SessionService {

    /** 登录成功后写入会话，TTL 与 JWT 有效期一致 */
    void createSession(String token, LoginSession session, long ttlSeconds);

    /** 取会话；不存在（登出/过期/Redis 重启）返回 null */
    LoginSession getSession(String token);

    /** 删除会话（登出） */
    void removeSession(String token);
}
