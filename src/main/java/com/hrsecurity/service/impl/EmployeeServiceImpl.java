package com.hrsecurity.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hrsecurity.common.BusinessException;
import com.hrsecurity.common.PageResult;
import com.hrsecurity.common.ResultCode;
import com.hrsecurity.dto.EmployeeDTO;
import com.hrsecurity.dto.EmployeeVO;
import com.hrsecurity.entity.SysDept;
import com.hrsecurity.entity.SysEmployee;
import com.hrsecurity.mapper.SysDeptMapper;
import com.hrsecurity.mapper.SysEmployeeMapper;
import com.hrsecurity.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmployeeServiceImpl implements EmployeeService {

    private final SysEmployeeMapper employeeMapper;
    private final SysDeptMapper deptMapper;

    @Override
    public PageResult<EmployeeVO> page(long pageNum, long pageSize, String keyword, Long deptId) {
        LambdaQueryWrapper<SysEmployee> wrapper = new LambdaQueryWrapper<>();
        // keyword 模糊匹配姓名或工号（逻辑删除已由 @TableLogic 自动过滤 status=0）
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(SysEmployee::getName, keyword)
                    .or()
                    .like(SysEmployee::getEmpNo, keyword));
        }
        if (deptId != null) {
            wrapper.eq(SysEmployee::getDeptId, deptId);
        }
        wrapper.orderByDesc(SysEmployee::getId);

        Page<SysEmployee> page = employeeMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        // 部门名一次性批量查询，避免逐条查部门的 N+1 问题
        Map<Long, String> deptNames = deptNameMap(page.getRecords().stream()
                .map(SysEmployee::getDeptId).collect(Collectors.toList()));
        List<EmployeeVO> records = page.getRecords().stream()
                .map(e -> toVO(e, deptNames.get(e.getDeptId())))
                .collect(Collectors.toList());
        return new PageResult<>(page.getTotal(), records);
    }

    @Override
    public EmployeeVO getById(Long id) {
        SysEmployee employee = employeeMapper.selectById(id);
        if (employee == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "员工不存在");
        }
        return toVO(employee, deptName(employee.getDeptId()));
    }

    @Override
    public EmployeeVO create(EmployeeDTO dto) {
        checkEmpNoUnique(dto.getEmpNo(), null);
        checkDeptExists(dto.getDeptId());

        SysEmployee employee = new SysEmployee();
        employee.setEmpNo(dto.getEmpNo());
        employee.setName(dto.getName());
        employee.setGender(dto.getGender() == null ? 1 : dto.getGender());
        employee.setPhone(dto.getPhone());
        employee.setEmail(dto.getEmail());
        employee.setDeptId(dto.getDeptId());
        employee.setEntryDate(dto.getEntryDate());
        employee.setStatus(1);
        employeeMapper.insert(employee);
        return toVO(employee, deptName(employee.getDeptId()));
    }

    @Override
    public EmployeeVO update(Long id, EmployeeDTO dto) {
        SysEmployee employee = employeeMapper.selectById(id);
        if (employee == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "员工不存在");
        }
        checkEmpNoUnique(dto.getEmpNo(), id);
        checkDeptExists(dto.getDeptId());

        employee.setEmpNo(dto.getEmpNo());
        employee.setName(dto.getName());
        if (dto.getGender() != null) {
            employee.setGender(dto.getGender());
        }
        employee.setPhone(dto.getPhone());
        employee.setEmail(dto.getEmail());
        employee.setDeptId(dto.getDeptId());
        employee.setEntryDate(dto.getEntryDate());
        employeeMapper.updateById(employee);
        return toVO(employee, deptName(employee.getDeptId()));
    }

    @Override
    public void delete(Long id) {
        // @TableLogic 生效：deleteById 实际执行 UPDATE status=0（离职）
        if (employeeMapper.deleteById(id) == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "员工不存在");
        }
    }

    /** 工号唯一校验（修改时排除自己） */
    private void checkEmpNoUnique(String empNo, Long excludeId) {
        LambdaQueryWrapper<SysEmployee> wrapper = new LambdaQueryWrapper<SysEmployee>()
                .eq(SysEmployee::getEmpNo, empNo);
        if (excludeId != null) {
            wrapper.ne(SysEmployee::getId, excludeId);
        }
        if (employeeMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "工号已存在");
        }
    }

    private void checkDeptExists(Long deptId) {
        if (deptMapper.selectById(deptId) == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "部门不存在");
        }
    }

    /** 批量查部门名，避免列表页 N+1 查询 */
    private Map<Long, String> deptNameMap(List<Long> deptIds) {
        List<Long> distinctIds = deptIds.stream().distinct().collect(Collectors.toList());
        if (distinctIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return deptMapper.selectBatchIds(distinctIds).stream()
                .collect(Collectors.toMap(SysDept::getId, SysDept::getDeptName, (a, b) -> a));
    }

    private String deptName(Long deptId) {
        return deptNameMap(Collections.singletonList(deptId)).get(deptId);
    }

    private EmployeeVO toVO(SysEmployee e, String deptName) {
        return EmployeeVO.builder()
                .id(e.getId())
                .empNo(e.getEmpNo())
                .name(e.getName())
                .gender(e.getGender())
                .phone(e.getPhone())
                .email(e.getEmail())
                .deptId(e.getDeptId())
                .deptName(deptName)
                .entryDate(e.getEntryDate())
                .status(e.getStatus())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
