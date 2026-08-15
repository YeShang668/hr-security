package com.hrsecurity.controller;

import com.hrsecurity.common.Result;
import com.hrsecurity.dto.LoginDTO;
import com.hrsecurity.dto.LoginResponse;
import com.hrsecurity.dto.RegisterDTO;
import com.hrsecurity.dto.UserInfoVO;
import com.hrsecurity.security.LoginUser;
import com.hrsecurity.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：注册 / 登录 / 当前用户
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 注册：POST /api/auth/register */
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterDTO dto) {
        authService.register(dto);
        return Result.success("注册成功", null);
    }

    /** 登录：POST /api/auth/login，返回 JWT */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginDTO dto) {
        return Result.success(authService.login(dto));
    }

    /**
     * 当前用户：GET /api/auth/me（需要带 token）
     * 用户身份来自 JwtAuthenticationFilter 放入 SecurityContext 的 LoginUser
     */
    @GetMapping("/me")
    public Result<UserInfoVO> me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        return Result.success(authService.getUserInfo(loginUser.getUid()));
    }
}
