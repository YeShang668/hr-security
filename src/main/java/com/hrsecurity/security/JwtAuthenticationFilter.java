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
import java.util.ArrayList;
import java.util.List;

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
 * 5. 除 ROLE_xxx 外，还把该用户的**权限编码**（如 employee:sensitive:read）作为 authority 放入，
 *    供细粒度鉴权使用（第 6 周：敏感字段明文查看权限），与角色一起走缓存、变更即时生效；
 * 6. 解析/会话校验失败 → 清空上下文，由 Security 统一返回 401 JSON。
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
                // 2. 从缓存/数据库取最新角色（ROLE_xxx）与权限编码，每次请求现取保证变更即时生效
                if (uid != null && sessionService.getSession(token) != null
                        && SecurityContextHolder.getContext().getAuthentication() == null) {
                    List<String> roles = roleService.getRoleCodes(uid);
                    List<String> permissions = roleService.getPermissionCodes(uid);
                    List<GrantedAuthority> authorities = toAuthorities(roles, permissions);
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

    /**
     * 角色 + 权限编码 → Spring Security 权限对象。
     * 角色加 ROLE_ 前缀供 hasRole('ADMIN') 使用；权限编码原样放入供 hasAuthority 使用。
     */
    private List<GrantedAuthority> toAuthorities(List<String> roles, List<String> permissions) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (roles != null) {
            roles.stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .forEach(authorities::add);
        }
        if (permissions != null) {
            permissions.stream()
                    .map(SimpleGrantedAuthority::new)
                    .forEach(authorities::add);
        }
        return authorities;
    }
}
