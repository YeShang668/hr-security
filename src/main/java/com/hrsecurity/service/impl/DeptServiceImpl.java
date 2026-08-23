package com.hrsecurity.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hrsecurity.common.BusinessException;
import com.hrsecurity.common.ResultCode;
import com.hrsecurity.dto.DeptDTO;
import com.hrsecurity.entity.SysDept;
import com.hrsecurity.entity.SysEmployee;
import com.hrsecurity.mapper.SysDeptMapper;
import com.hrsecurity.mapper.SysEmployeeMapper;
import com.hrsecurity.service.DeptService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DeptServiceImpl implements DeptService {

    private final SysDeptMapper deptMapper;
    private final SysEmployeeMapper employeeMapper;

    @Override
    public List<SysDept> list() {
        // 平铺列表按 sort 升序（多级树形组织架构 9 月演进）
        return deptMapper.selectList(
                new LambdaQueryWrapper<SysDept>().orderByAsc(SysDept::getSort));
    }

    @Override
    public SysDept create(DeptDTO dto) {
        SysDept dept = new SysDept();
        dept.setDeptName(dto.getDeptName());
        dept.setParentId(dto.getParentId() == null ? 0L : dto.getParentId());
        dept.setSort(dto.getSort() == null ? 0 : dto.getSort());
        dept.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        deptMapper.insert(dept);
        return dept;
    }

    @Override
    public SysDept update(Long id, DeptDTO dto) {
        SysDept dept = deptMapper.selectById(id);
        if (dept == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "部门不存在");
        }
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
        deptMapper.updateById(dept);
        return dept;
    }

    @Override
    public void delete(Long id) {
        if (deptMapper.selectById(id) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "部门不存在");
        }
        // 部门下还有员工（含逻辑删除过滤后的在职员工）时禁止删除，防止产生孤儿数据
        Long employeeCount = employeeMapper.selectCount(
                new LambdaQueryWrapper<SysEmployee>().eq(SysEmployee::getDeptId, id));
        if (employeeCount != null && employeeCount > 0) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "部门下存在员工，无法删除");
        }
        deptMapper.deleteById(id);
    }
}
