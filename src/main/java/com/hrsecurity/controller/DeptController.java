package com.hrsecurity.controller;

import com.hrsecurity.common.Result;
import com.hrsecurity.dto.DeptDTO;
import com.hrsecurity.entity.SysDept;
import com.hrsecurity.service.DeptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 部门管理接口。
 * 权限：查看需登录（ADMIN/EMPLOYEE 均可），增删改仅 ADMIN。
 */
@RestController
@RequestMapping("/api/depts")
@RequiredArgsConstructor
public class DeptController {

    private final DeptService deptService;

    /** 部门列表（按 sort 排序） */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'EMPLOYEE')")
    public Result<List<SysDept>> list() {
        return Result.success(deptService.list());
    }

    /** 新增部门 */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<SysDept> create(@Valid @RequestBody DeptDTO dto) {
        return Result.success("新增成功", deptService.create(dto));
    }

    /** 修改部门 */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<SysDept> update(@PathVariable Long id, @Valid @RequestBody DeptDTO dto) {
        return Result.success("修改成功", deptService.update(id, dto));
    }

    /** 删除部门（部门下存在员工时返回 409） */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        deptService.delete(id);
        return Result.success("删除成功", null);
    }
}
