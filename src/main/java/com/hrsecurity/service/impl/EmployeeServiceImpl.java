package com.hrsecurity.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hrsecurity.common.BusinessException;
import com.hrsecurity.common.PageResult;
import com.hrsecurity.common.ResultCode;
import com.hrsecurity.crypto.FieldHashUtil;
import com.hrsecurity.crypto.MaskingUtil;
import com.hrsecurity.dto.EmployeeDTO;
import com.hrsecurity.dto.EmployeeSensitiveVO;
import com.hrsecurity.dto.EmployeeVO;
import com.hrsecurity.entity.SysDept;
import com.hrsecurity.entity.SysEmployee;
import com.hrsecurity.mapper.SysDeptMapper;
import com.hrsecurity.mapper.SysEmployeeMapper;
import com.hrsecurity.security.PermissionCodes;
import com.hrsecurity.security.SecurityUtils;
import com.hrsecurity.service.EmployeeService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 员工管理实现。
 *
 * 第 6 周的两条主线都在这里：
 * 1. **写入加密**：DTO 明文 → 实体 → TypeHandler 加密落库，Service 不感知密文；
 * 2. **读出脱敏**：出参 VO 一律先脱敏，只有 getSensitive（独立权限、独立接口）给明文。
 * 脱敏判定集中在这个类的 toVO，不散落在各 Controller——避免哪天新加接口忘了脱敏。
 */
@Service
public class EmployeeServiceImpl extends BaseServiceImpl<SysEmployeeMapper, SysEmployee> implements EmployeeService {

    private final SysDeptMapper deptMapper;
    private final FieldHashUtil fieldHashUtil;

    public EmployeeServiceImpl(SysEmployeeMapper employeeMapper, SysDeptMapper deptMapper,
                               FieldHashUtil fieldHashUtil) {
        super(employeeMapper);
        this.deptMapper = deptMapper;
        this.fieldHashUtil = fieldHashUtil;
    }

    @Override
    public PageResult<EmployeeVO> page(long pageNum, long pageSize, String keyword, Long deptId) {
        LambdaQueryWrapper<SysEmployee> wrapper = new LambdaQueryWrapper<>();
        // keyword 模糊匹配姓名或工号（逻辑删除已由 @TableLogic 自动过滤 status=0）。
        // 刻意不支持按手机号/身份证模糊查：那些列是密文，like 匹配不到任何东西，
        // 真需要按身份证查走 searchByIdCard（哈希等值匹配）
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(SysEmployee::getName, keyword)
                    .or()
                    .like(SysEmployee::getEmpNo, keyword));
        }
        if (deptId != null) {
            wrapper.eq(SysEmployee::getDeptId, deptId);
        }
        wrapper.orderByDesc(SysEmployee::getId);

        Page<SysEmployee> page = baseMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
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
        SysEmployee employee = getOrThrow(id, "员工不存在");
        return toVO(employee, deptName(employee.getDeptId()));
    }

    @Override
    public EmployeeSensitiveVO getSensitive(Long id) {
        // 权限由 Controller 的 @PreAuthorize 把关（employee:sensitive:read），
        // 这个方法的职责就是"明文的唯一出口"（第 7 周会在这里挂审计埋点）
        SysEmployee e = getOrThrow(id, "员工不存在");
        return EmployeeSensitiveVO.builder()
                .id(e.getId())
                .empNo(e.getEmpNo())
                .name(e.getName())
                .idCard(e.getIdCardEnc())
                .phone(e.getPhoneEnc())
                .bankCard(e.getBankCardEnc())
                .salary(e.getSalaryEnc())
                .build();
    }

    @Override
    public List<EmployeeVO> searchByIdCard(String idCard) {
        if (!StringUtils.hasText(idCard)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "身份证号不能为空");
        }
        // 只比对哈希列：不解密任何密文、不扫全表（哈希列有唯一索引），
        // 顺带绕开了"拿明文去 like 密文列"这种必然查不到的做法
        String hash = fieldHashUtil.hmacSha256Hex(idCard);
        List<SysEmployee> employees = baseMapper.selectList(
                new LambdaQueryWrapper<SysEmployee>().eq(SysEmployee::getIdCardHash, hash));
        return employees.stream()
                .map(e -> toVO(e, deptName(e.getDeptId())))
                .collect(Collectors.toList());
    }

    @Override
    public EmployeeVO create(EmployeeDTO dto) {
        checkEmpNoUnique(dto.getEmpNo(), null);
        checkDeptExists(dto.getDeptId());

        SysEmployee employee = new SysEmployee();
        employee.setEmpNo(dto.getEmpNo());
        employee.setName(dto.getName());
        employee.setGender(dto.getGender() == null ? 1 : dto.getGender());
        employee.setEmail(dto.getEmail());
        employee.setDeptId(dto.getDeptId());
        employee.setEntryDate(dto.getEntryDate());
        employee.setStatus(1);
        // 敏感字段：明文进实体，落库由 AesTypeHandler 加密；身份证同时维护检索哈希
        applySensitive(employee, dto, null);
        baseMapper.insert(employee);
        return toVO(employee, deptName(employee.getDeptId()));
    }

    @Override
    public EmployeeVO update(Long id, EmployeeDTO dto) {
        SysEmployee employee = getOrThrow(id, "员工不存在");
        checkEmpNoUnique(dto.getEmpNo(), id);
        checkDeptExists(dto.getDeptId());

        employee.setEmpNo(dto.getEmpNo());
        employee.setName(dto.getName());
        if (dto.getGender() != null) {
            employee.setGender(dto.getGender());
        }
        employee.setEmail(dto.getEmail());
        employee.setDeptId(dto.getDeptId());
        employee.setEntryDate(dto.getEntryDate());
        // 敏感字段留空 = 不修改（防"脱敏值被回填提交"把真实数据覆盖成 138****0001）
        applySensitive(employee, dto, id);
        baseMapper.updateById(employee);
        return toVO(employee, deptName(employee.getDeptId()));
    }

    @Override
    public void delete(Long id) {
        // @TableLogic 生效：deleteById 实际执行 UPDATE status=0（离职）
        if (baseMapper.deleteById(id) == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "员工不存在");
        }
    }

    /**
     * 写入敏感字段：非空才覆盖（新增/修改共用）。
     * excludeId 非空表示修改场景，身份证唯一校验要排除自己。
     */
    private void applySensitive(SysEmployee employee, EmployeeDTO dto, Long excludeId) {
        if (StringUtils.hasText(dto.getIdCard())) {
            checkIdCardUnique(dto.getIdCard(), excludeId);
            employee.setIdCardEnc(dto.getIdCard());
            employee.setIdCardHash(fieldHashUtil.hmacSha256Hex(dto.getIdCard()));
        }
        if (StringUtils.hasText(dto.getPhone())) {
            employee.setPhoneEnc(dto.getPhone());
        }
        if (StringUtils.hasText(dto.getBankCard())) {
            employee.setBankCardEnc(dto.getBankCard());
        }
        if (dto.getSalary() != null) {
            // 统一成字符串存储（金额精度由 BigDecimal 在入口校验保证，避免 double 精度问题）
            employee.setSalaryEnc(dto.getSalary().toPlainString());
        }
    }

    /** 是否有查看敏感明文的权限（决定列表/详情返回明文还是脱敏值） */
    private boolean canReadSensitive() {
        return SecurityUtils.hasAuthority(PermissionCodes.EMPLOYEE_SENSITIVE_READ);
    }

    /** 工号唯一校验（修改时排除自己） */
    private void checkEmpNoUnique(String empNo, Long excludeId) {
        LambdaQueryWrapper<SysEmployee> wrapper = new LambdaQueryWrapper<SysEmployee>()
                .eq(SysEmployee::getEmpNo, empNo);
        if (excludeId != null) {
            wrapper.ne(SysEmployee::getId, excludeId);
        }
        if (baseMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "工号已存在");
        }
    }

    /**
     * 身份证唯一校验：密文每次加密结果都不同（IV 随机），没法拿密文比；
     * 用 id_card_hash 等值查询即可——这正是"可检索性设计"的第二个用途（第一个是查询本身）。
     */
    private void checkIdCardUnique(String idCard, Long excludeId) {
        LambdaQueryWrapper<SysEmployee> wrapper = new LambdaQueryWrapper<SysEmployee>()
                .eq(SysEmployee::getIdCardHash, fieldHashUtil.hmacSha256Hex(idCard));
        if (excludeId != null) {
            wrapper.ne(SysEmployee::getId, excludeId);
        }
        if (baseMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "身份证号已存在");
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

    /**
     * 实体 → VO。
     *
     * 关键决策：列表/详情**对所有角色一律脱敏**（连 ADMIN 也一样），明文只从
     * {@link #getSensitive} 这一个出口出。这样"看不看得到明文"永远是一次显式行为，
     * 而不是"翻列表时顺手带出来的"，审计（第 7 周）也只需盯住那一个出口。
     * sensitiveVisible 只用于告诉前端"这个账号能不能点查看完整信息"，不参与脱敏判断。
     */
    private EmployeeVO toVO(SysEmployee e, String deptName) {
        return EmployeeVO.builder()
                .id(e.getId())
                .empNo(e.getEmpNo())
                .name(e.getName())
                .gender(e.getGender())
                .phone(MaskingUtil.maskPhone(e.getPhoneEnc()))
                .idCard(MaskingUtil.maskIdCard(e.getIdCardEnc()))
                .bankCard(MaskingUtil.maskBankCard(e.getBankCardEnc()))
                .salary(MaskingUtil.maskSalary(e.getSalaryEnc()))
                .sensitiveVisible(canReadSensitive())
                .email(e.getEmail())
                .deptId(e.getDeptId())
                .deptName(deptName)
                .entryDate(e.getEntryDate())
                .status(e.getStatus())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
