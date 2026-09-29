package com.hrsecurity.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hrsecurity.audit.AuditLog;
import com.hrsecurity.audit.AuditTrace;
import com.hrsecurity.common.BusinessException;
import com.hrsecurity.common.ResultCode;
import com.hrsecurity.dto.DeptDTO;
import com.hrsecurity.entity.SysDept;
import com.hrsecurity.entity.SysEmployee;
import com.hrsecurity.mapper.SysDeptMapper;
import com.hrsecurity.mapper.SysEmployeeMapper;
import com.hrsecurity.service.DeptService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 部门管理实现。
 * 第 7 周：增删改同样记审计——部门是"数据权限"的维度，
 * 组织结构被谁在什么时候改动，是权限审计的一部分（普通管理动作，记 INFO 级即可）。
 */
@Service
public class DeptServiceImpl extends BaseServiceImpl<SysDeptMapper, SysDept> implements DeptService {

    private final SysEmployeeMapper employeeMapper;

    public DeptServiceImpl(SysDeptMapper deptMapper, SysEmployeeMapper employeeMapper) {
        super(deptMapper);
        this.employeeMapper = employeeMapper;
    }

    @Override
    public List<SysDept> list() {
        // 平铺列表按 sort 升序（多级树形组织架构 9 月演进）
        return baseMapper.selectList(
                new LambdaQueryWrapper<SysDept>().orderByAsc(SysDept::getSort));
    }

    @AuditLog(operation = "新增部门", targetType = "DEPT")
    @Override
    public SysDept create(DeptDTO dto) {
        SysDept dept = new SysDept();
        dept.setDeptName(dto.getDeptName());
        dept.setParentId(dto.getParentId() == null ? 0L : dto.getParentId());
        dept.setSort(dto.getSort() == null ? 0 : dto.getSort());
        dept.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        baseMapper.insert(dept);
        AuditTrace.append("部门名 " + dept.getDeptName());
        return dept;
    }

    @AuditLog(operation = "修改部门", targetType = "DEPT", targetIdArgIndex = 0)
    @Override
    public SysDept update(Long id, DeptDTO dto) {
        SysDept dept = getOrThrow(id, "部门不存在");
        dept.setDeptName(dto.getDeptName());
        if (dto.getParentId() != null) {
            dept.setParentId(dto.getParentId());
        }
        if (dto.getSort() != null) {
            dept.setSort(dto.getSort());
        }
        if (dto.getStatus() != null) {
            dept.setStatus(dto.getStatus());
        }
        baseMapper.updateById(dept);
        AuditTrace.append("部门名 " + dept.getDeptName());
        return dept;
    }

    @AuditLog(operation = "删除部门", targetType = "DEPT", targetIdArgIndex = 0)
    @Override
    public void delete(Long id) {
        SysDept dept = getOrThrow(id, "部门不存在");
        // 部门下还有员工（含逻辑删除过滤后的在职员工）时禁止删除，防止产生孤儿数据
        Long employeeCount = employeeMapper.selectCount(
                new LambdaQueryWrapper<SysEmployee>().eq(SysEmployee::getDeptId, id));
        if (employeeCount != null && employeeCount > 0) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "部门下存在员工，无法删除");
        }
        baseMapper.deleteById(id);
        AuditTrace.append("部门名 " + dept.getDeptName());
    }
}
