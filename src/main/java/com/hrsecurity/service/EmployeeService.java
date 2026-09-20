package com.hrsecurity.service;

import com.hrsecurity.common.PageResult;
import com.hrsecurity.dto.EmployeeDTO;
import com.hrsecurity.dto.EmployeeSensitiveVO;
import com.hrsecurity.dto.EmployeeVO;

import java.util.List;

/**
 * 员工管理：分页查询 + 增删改 + 敏感信息明文查询。
 * 删除为逻辑删除（status 置 0 = 离职），查询自动过滤已离职员工。
 *
 * 敏感字段策略（第 6 周）：
 * - 写入：DTO 里的明文交给 TypeHandler 加密落库，Service 当普通字段用；
 * - 读出：VO 一律脱敏，只有 getSensitive 这一条出口返回明文，且接口层要求
 *   employee:sensitive:read 权限。
 */
public interface EmployeeService {

    /** 分页查询，keyword 模糊匹配姓名/工号，deptId 可选过滤（敏感字段返回脱敏值） */
    PageResult<EmployeeVO> page(long pageNum, long pageSize, String keyword, Long deptId);

    /** 详情（敏感字段返回脱敏值） */
    EmployeeVO getById(Long id);

    /** 敏感信息明文（身份证/手机号/银行卡/工资），仅限有敏感权限的调用者 */
    EmployeeSensitiveVO getSensitive(Long id);

    /**
     * 按身份证号精确查询员工。
     * 密文列无法做 like/等值查询，这里用 id_card_hash（HMAC-SHA256）匹配，
     * 不解密、不扫全表，是"加密字段仍可精确检索"的落地方式。
     */
    List<EmployeeVO> searchByIdCard(String idCard);

    EmployeeVO create(EmployeeDTO dto);

    EmployeeVO update(Long id, EmployeeDTO dto);

    void delete(Long id);
}
