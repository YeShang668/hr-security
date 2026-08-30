package com.hrsecurity.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * JWT 工具类（jjwt 0.12 API）。
 *
 * 密钥优先级：环境变量 JWT_SECRET > application-local.yml 的 jwt.secret。
 * 生产环境必须用环境变量注入随机密钥，禁止硬编码在代码里。
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expire-hours:24}")
    private long expireHours;

    private SecretKey key;

    @PostConstruct
    public void init() {
        // 环境变量优先，保证生产不依赖配置文件
        String env = System.getenv("JWT_SECRET");
        if (env != null && !env.isBlank()) {
            secret = env;
        }
        // HS256 要求密钥至少 32 字节
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 签发 token，payload 里带 uid、用户名和角色编码 */
    public String createToken(Long userId, String username, List<String> roles) {
        Date now = new Date();
        Date expire = new Date(now.getTime() + getExpireSeconds() * 1000);
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .claim("roles", roles)
                .issuedAt(now)
                .expiration(expire)
                .signWith(key)
                .compact();
    }

    /** JWT 有效期（秒），Redis 会话 TTL 与其保持一致，保证两者同时过期 */
    public long getExpireSeconds() {
        return expireHours * 3600;
    }

    /**
     * 解析 token。签名错误/过期/格式非法都会抛 JwtException，
     * 调用方（过滤器）统一当作"未登录"处理。
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 从 Claims 中取用户 id（解析失败返回 null，由调用方判断） */
    public Long getUserId(String token) {
        try {
            return parseToken(token).get("uid", Long.class);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
