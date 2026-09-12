package com.hrsecurity.controller;

import com.hrsecurity.common.Result;
import com.hrsecurity.entity.SysRole;
import com.hrsecurity.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色列表接口：给"用户管理 → 分配角色"的下拉框用，仅 ADMIN 可读。
 * 返回 SysRole 实体（只含 roleCode/roleName/status，无敏感字段，无需 VO）。
 */
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    /** 全部启用角色：GET /api/roles */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<SysRole>> list() {
        return Result.success(roleService.listAll());
    }
}
