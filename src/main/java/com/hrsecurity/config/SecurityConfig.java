package com.hrsecurity.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrsecurity.common.Result;
import com.hrsecurity.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * Spring Security 6 配置（SecurityFilterChain 写法，不用已废弃的 WebSecurityConfigurerAdapter）。
 *
 * 思路：
 * - 无状态会话：不用 Session，token 放请求头，天然支持前后端分离；
 * - 白名单：login/register 不需要登录，其余接口一律要求认证；
 * - 未登录(401)/无权限(403) 时直接写 JSON，而不是跳转登录页；
 * - 把 JwtAuthenticationFilter 挂在用户名密码过滤器之前，先验 token；
 * - @EnableMethodSecurity 开启方法级鉴权，配合 Controller 上的 @PreAuthorize 做接口权限。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            // 前后端分离：不创建 Session，所有状态靠 JWT
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // login/register/logout 不需要认证（logout 的 token 在 Controller 内部解析）
                .requestMatchers("/api/auth/login", "/api/auth/register", "/api/auth/logout").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> writeJson(res, 401, "未登录或登录已过期"))
                .accessDeniedHandler((req, res, ex) -> writeJson(res, 403, "无权限访问")))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt：每次加密自动加随机盐，相同明文两次加密结果不同，且自带抗彩虹表能力
        return new BCryptPasswordEncoder();
    }

    /** 把错误信息写成统一格式的 JSON */
    private void writeJson(HttpServletResponse res, int status, String msg) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write(objectMapper.writeValueAsString(Result.error(status, msg)));
    }
}
