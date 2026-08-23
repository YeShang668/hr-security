package com.hrsecurity.controller;

import com.hrsecurity.common.PageResult;
import com.hrsecurity.common.Result;
import com.hrsecurity.dto.EmployeeDTO;
import com.hrsecurity.dto.EmployeeVO;
import com.hrsecurity.service.EmployeeService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 员工管理接口。
 * 权限：查询需登录（ADMIN/EMPLOYEE 均可），增删改仅 ADMIN。
 * 删除为逻辑删除（离职），已离职员工不出现在查询结果中。
 */
@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;

    /** 员工分页列表：GET /api/employees?page=1&size=10&keyword=张&deptId=1 */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'EMPLOYEE')")
    public Result<PageResult<EmployeeVO>> page(@RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "10") long size,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) Long deptId) {
        return Result.success(employeeService.page(page, size, keyword, deptId));
    }

    /** 员工详情 */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'EMPLOYEE')")
    public Result<EmployeeVO> getById(@PathVariable Long id) {
        return Result.success(employeeService.getById(id));
    }

    /** 新增员工 */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<EmployeeVO> create(@Valid @RequestBody EmployeeDTO dto) {
        return Result.success("新增成功", employeeService.create(dto));
    }

    /** 修改员工 */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<EmployeeVO> update(@PathVariable Long id, @Valid @RequestBody EmployeeDTO dto) {
        return Result.success("修改成功", employeeService.update(id, dto));
    }

    /** 删除员工（逻辑删除 = 离职） */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        employeeService.delete(id);
        return Result.success("删除成功", null);
    }
}
