package com.hrsecurity.service;

import com.hrsecurity.common.PageResult;
import com.hrsecurity.dto.EmployeeDTO;
import com.hrsecurity.dto.EmployeeVO;

/**
 * 员工管理：分页查询 + 增删改。
 * 删除为逻辑删除（status 置 0 = 离职），查询自动过滤已离职员工。
 */
public interface EmployeeService {

    /** 分页查询，keyword 模糊匹配姓名/工号，deptId 可选过滤 */
    PageResult<EmployeeVO> page(long pageNum, long pageSize, String keyword, Long deptId);

    EmployeeVO getById(Long id);

    EmployeeVO create(EmployeeDTO dto);

    EmployeeVO update(Long id, EmployeeDTO dto);

    void delete(Long id);
}
