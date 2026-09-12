package com.hrsecurity.service.impl;

import com.hrsecurity.dto.LoginSession;
import com.hrsecurity.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * Redis 会话存储，两个 key 配合使用：
 * - login:token:{token} → 会话内容（uid/username），TTL 与 JWT 一致，是"登录态有效"的依据；
 * - login:user:{userId}  → 该用户当前所有 token 的集合（索引），TTL 同上。
 *
 * 为什么要第二个 key（面试点）：只存 token 的话，管理员禁用账号时拿不到
 * "这个人手上的所有 token"，只能等它自然过期；有了索引就能立刻全部删掉，
 * 实现"禁用即踢下线"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionServiceImpl implements SessionService {

    /** 会话 key：login:token:{token} */
    private static final String SESSION_KEY_PREFIX = "login:token:";

    /** 用户会话索引 key：login:user:{userId} → Set<token> */
    private static final String USER_SESSIONS_KEY_PREFIX = "login:user:";

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public void createSession(String token, LoginSession session, long ttlSeconds) {
        Duration ttl = Duration.ofSeconds(ttlSeconds);
        redisTemplate.opsForValue().set(SESSION_KEY_PREFIX + token, session, ttl);
        // 同一用户多次登录产生多个 token，全部记进索引；集合本身也带 TTL，
        // 即使没有登出，索引也会随会话一起过期，不会无限膨胀
        String userKey = USER_SESSIONS_KEY_PREFIX + session.getUid();
        redisTemplate.opsForSet().add(userKey, token);
        redisTemplate.expire(userKey, ttl);
    }

    @Override
    public LoginSession getSession(String token) {
        Object value = redisTemplate.opsForValue().get(SESSION_KEY_PREFIX + token);
        return value instanceof LoginSession session ? session : null;
    }

    @Override
    public void removeSession(String token) {
        // 先读会话拿到 uid，才能把 token 从用户索引里摘掉（避免索引变成垃圾集合）
        LoginSession session = getSession(token);
        if (session != null && session.getUid() != null) {
            redisTemplate.opsForSet().remove(USER_SESSIONS_KEY_PREFIX + session.getUid(), token);
        }
        redisTemplate.delete(SESSION_KEY_PREFIX + token);
    }

    @Override
    public void removeAllSessions(Long userId) {
        String userKey = USER_SESSIONS_KEY_PREFIX + userId;
        Set<Object> tokens = redisTemplate.opsForSet().members(userKey);
        if (tokens == null || tokens.isEmpty()) {
            log.info("用户 {} 无在线会话，禁用踢下线跳过", userId);
            return;
        }
        // 逐个删会话 key，最后删索引本身
        for (Object token : tokens) {
            redisTemplate.delete(SESSION_KEY_PREFIX + token);
        }
        redisTemplate.delete(userKey);
        log.info("用户 {} 的 {} 个会话已全部删除（禁用踢下线）", userId, tokens.size());
    }
}
