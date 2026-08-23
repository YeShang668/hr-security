package com.hrsecurity.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 每次请求都会经过的过滤器：从请求头取出 JWT → 校验 → 把登录用户放进 SecurityContext。
 *
 * 流程：
 * 1. 取 Authorization 头，必须是 "Bearer xxx" 格式；
 * 2. 解析失败（过期/篡改）→ 清空上下文，放行；
 *    后续 Security 发现该请求需要登录时，会统一返回 401 JSON；
 * 3. 解析成功 → 构造 LoginUser 放入 SecurityContext，后续接口直接取用。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                Claims claims = jwtUtil.parseToken(token);
                Long uid = claims.get("uid", Long.class);
                String username = claims.getSubject();
                if (uid != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    // 从 JWT claims 读角色编码，转成 ROLE_xxx 权限（hasRole 自动补 ROLE_ 前缀）
                    List<String> roles = claims.get("roles", List.class);
                    List<GrantedAuthority> authorities = toAuthorities(roles);
                    LoginUser loginUser = new LoginUser(uid, username,
                            roles == null ? Collections.emptyList() : roles);
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(loginUser, null, authorities);
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (JwtException | IllegalArgumentException e) {
                // token 无效/过期：清空上下文，由 Security 统一返回 401
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    /** 角色编码列表 → Spring Security 权限对象；token 里没有 roles（旧 token）时按无权限处理 */
    @SuppressWarnings("unchecked")
    private List<GrantedAuthority> toAuthorities(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Collections.emptyList();
        }
        return roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toList());
    }
}
