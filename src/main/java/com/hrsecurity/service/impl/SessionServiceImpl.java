package com.hrsecurity.service.impl;

import com.hrsecurity.dto.LoginSession;
import com.hrsecurity.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class SessionServiceImpl implements SessionService {

    /** 会话 key：login:token:{token} */
    private static final String SESSION_KEY_PREFIX = "login:token:";

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public void createSession(String token, LoginSession session, long ttlSeconds) {
        redisTemplate.opsForValue().set(SESSION_KEY_PREFIX + token, session, Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public LoginSession getSession(String token) {
        Object value = redisTemplate.opsForValue().get(SESSION_KEY_PREFIX + token);
        return value instanceof LoginSession session ? session : null;
    }

    @Override
    public void removeSession(String token) {
        redisTemplate.delete(SESSION_KEY_PREFIX + token);
    }
}
