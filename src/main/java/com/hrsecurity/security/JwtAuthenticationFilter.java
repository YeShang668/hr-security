package com.hrsecurity.security;

import com.hrsecurity.service.RoleService;
import com.hrsecurity.service.SessionService;
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
 * 第 3 周改造后的流程（引入 Redis 会话与权限缓存）：
 * 1. 取 Authorization 头，必须是 "Bearer xxx" 格式；
 * 2. JWT 验签（防伪造/防篡改），解析出 uid/username；
 * 3. 校验 Redis 会话 login:token:{token} 存在——不存在 = 已登出/会话过期 = 401
 *    （登出即失效，服务端可随时踢人）；
 * 4. 角色不再信 JWT claims 里的旧值，从角色缓存/数据库取**最新**角色，
 *    角色变更后下一次请求立即生效；
 * 5. 解析/会话校验失败 → 清空上下文，由 Security 统一返回 401 JSON。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final SessionService sessionService;
    private final RoleService roleService;

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
                // 1. token 验签通过，但 Redis 会话不存在（登出/过期）→ 按未登录处理
                // 2. 从角色缓存/数据库取最新角色，转 ROLE_xxx 权限（hasRole 自动补 ROLE_ 前缀）
                if (uid != null && sessionService.getSession(token) != null
                        && SecurityContextHolder.getContext().getAuthentication() == null) {
                    List<String> roles = roleService.getRoleCodes(uid);
                    List<GrantedAuthority> authorities = toAuthorities(roles);
                    LoginUser loginUser = new LoginUser(uid, username, roles);
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(loginUser, null, authorities);
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } else {
                    // token 有效但无会话：已登出或服务端失效，视为未登录
                    SecurityContextHolder.clearContext();
                }
            } catch (JwtException | IllegalArgumentException e) {
                // token 无效/过期：清空上下文，由 Security 统一返回 401
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    /** 角色编码列表 → Spring Security 权限对象；无角色时按无权限处理 */
    private List<GrantedAuthority> toAuthorities(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Collections.emptyList();
        }
        return roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toList());
    }
}
