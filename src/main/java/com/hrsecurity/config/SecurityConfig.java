package com.hrsecurity.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrsecurity.common.Result;
import com.hrsecurity.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

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
            // CSRF 为什么可以关（不是"偷懒"，见 docs/security-hardening.md）：
            // 本项目鉴权只认 Authorization 头里的 JWT，服务端不建 Session、不读 Cookie，
            // 浏览器不会自动携带该头 —— 攻击者站点无法让受害者的浏览器"替我发一个带 token 的请求"，
            // CSRF 的成立前提（凭据自动附带）不成立。反过来说：**一旦改成 Cookie 存 token，
            // 这个 disable 就必须撤掉并启用 CSRF token**。
            .csrf(AbstractHttpConfigurer::disable)
            // 前后端分离：不创建 Session，所有状态靠 JWT
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // 安全响应头（第 8 周）：默认只对"浏览器直接渲染"的响应有意义，
            // 一个纯 JSON API 也值得加——接口可能被 <iframe>/<script>/直接打开，加了才是显式声明"我不接受这种用法"
            .headers(h -> h
                // 禁止浏览器按内容嗅探类型：即使用户名里存了 <script>，响应也不会被当 HTML/JS 执行
                .contentTypeOptions(Customizer.withDefaults())
                // 禁止被任意站点用 iframe 嵌入（点击劫持）
                .frameOptions(f -> f.deny())
                // API 不需要 referrer 外泄
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                // HSTS：只在**请求本身是 https** 时才写（Spring Security 用 request.isSecure() 判断），
                // 所以它依赖 server.forward-headers-strategy 让反代后的 https 被识别（见 application.yml）
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31_536_000))
                // 基础 CSP：本服务只产出 JSON，任何"加载/执行资源"的能力都不需要，直接全禁
                .addHeaderWriter(new StaticHeadersWriter("Content-Security-Policy",
                        "default-src 'none'; frame-ancestors 'none'; base-uri 'none'")))
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
