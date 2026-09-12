package com.hrsecurity.controller;

import com.hrsecurity.common.PageResult;
import com.hrsecurity.common.Result;
import com.hrsecurity.dto.UserRolesDTO;
import com.hrsecurity.dto.UserStatusDTO;
import com.hrsecurity.dto.UserVO;
import com.hrsecurity.security.LoginUser;
import com.hrsecurity.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理接口（第 5 周新增，配合前端"用户管理"页）。
 * 权限：全部接口仅 ADMIN——用户与角色是最敏感的配置，普通员工连列表都不给看。
 * 注意：这里的角色分配是"角色 → 权限"的上游，改完删缓存即时生效。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** 用户分页列表：GET /api/users?page=1&size=10&keyword=zhang&status=1 */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<PageResult<UserVO>> page(@RequestParam(defaultValue = "1") long page,
                                           @RequestParam(defaultValue = "10") long size,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) Integer status) {
        return Result.success(userService.page(page, size, keyword, status));
    }

    /** 启用/禁用账号：禁用会同时踢掉该用户在线会话 */
    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateStatus(@PathVariable Long id,
                                     @Valid @RequestBody UserStatusDTO dto,
                                     @AuthenticationPrincipal LoginUser currentUser) {
        userService.updateStatus(id, dto.getStatus(), currentUser.getUid());
        return Result.success(dto.getStatus() == 1 ? "已启用" : "已禁用", null);
    }

    /** 分配角色（覆盖式）：改完旧 token 立即获得/失去对应权限 */
    @PutMapping("/{id}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> assignRoles(@PathVariable Long id,
                                    @Valid @RequestBody UserRolesDTO dto,
                                    @AuthenticationPrincipal LoginUser currentUser) {
        userService.assignRoles(id, dto.getRoleIds(), currentUser.getUid());
        return Result.success("角色已更新", null);
    }
}
