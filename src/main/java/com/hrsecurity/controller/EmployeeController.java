package com.hrsecurity.controller;

import com.hrsecurity.common.PageResult;
import com.hrsecurity.common.Result;
import com.hrsecurity.dto.EmployeeDTO;
import com.hrsecurity.dto.EmployeeSensitiveVO;
import com.hrsecurity.dto.EmployeeVO;
import com.hrsecurity.security.PermissionCodes;
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

import java.util.List;

/**
 * 员工管理接口。
 * 权限：查询需登录（ADMIN/EMPLOYEE 均可），增删改仅 ADMIN。
 * 删除为逻辑删除（离职），已离职员工不出现在查询结果中。
 *
 * 第 6 周敏感数据出口约定：
 * - 列表 / 详情：敏感字段**一律脱敏**（连 ADMIN 也脱敏）——最小披露原则，
 *   明文必须是显式动作；
 * - /{id}/sensitive：唯一明文的出口，需要 employee:sensitive:read 权限（默认仅 ADMIN），
 *   便于审计（第 7 周 AOP 只盯这一处）与前端"查看完整信息"按钮对应。
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

    /**
     * 按身份证号精确查询（密文不可 like，走 HMAC 哈希等值匹配）。
     * 能查身份证本身就属于敏感操作，因此同样要求敏感权限，且出参仍是脱敏值。
     */
    @GetMapping("/search")
    @PreAuthorize("hasAuthority('" + PermissionCodes.EMPLOYEE_SENSITIVE_READ + "')")
    public Result<List<EmployeeVO>> searchByIdCard(@RequestParam String idCard) {
        return Result.success(employeeService.searchByIdCard(idCard));
    }

    /** 员工详情（敏感字段脱敏） */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'EMPLOYEE')")
    public Result<EmployeeVO> getById(@PathVariable Long id) {
        return Result.success(employeeService.getById(id));
    }

    /**
     * 敏感信息明文：身份证 / 手机号 / 银行卡 / 工资。
     * 独立权限 + 独立接口 = 明文只在这一个出口出现（脱敏是默认，明文是显式授权）。
     */
    @GetMapping("/{id}/sensitive")
    @PreAuthorize("hasAuthority('" + PermissionCodes.EMPLOYEE_SENSITIVE_READ + "')")
    public Result<EmployeeSensitiveVO> getSensitive(@PathVariable Long id) {
        return Result.success(employeeService.getSensitive(id));
    }

    /** 新增员工（仅 ADMIN）：敏感字段明文传入，落库自动加密 */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<EmployeeVO> create(@Valid @RequestBody EmployeeDTO dto) {
        return Result.success("新增成功", employeeService.create(dto));
    }

    /** 修改员工（仅 ADMIN）：敏感字段留空表示不修改 */
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
