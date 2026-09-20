package com.hrsecurity.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 从 SecurityContext 取当前登录用户与权限判断，避免每个 Service 重复写样板代码。
 * 脱敏逻辑需要知道"当前调用者有没有敏感明文权限"，就在这里统一取。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /** 当前登录用户；未登录（如内部任务线程）返回 null */
    public static LoginUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser;
        }
        return null;
    }

    /** 当前登录用户 id；未登录返回 null */
    public static Long currentUserId() {
        LoginUser user = currentUser();
        return user == null ? null : user.getUid();
    }

    /** 是否具备某个权限编码（如 employee:sensitive:read） */
    public static boolean hasAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authority == null) {
            return false;
        }
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            if (authority.equals(granted.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
