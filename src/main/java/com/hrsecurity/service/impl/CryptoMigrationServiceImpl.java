package com.hrsecurity.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hrsecurity.common.BusinessException;
import com.hrsecurity.common.ResultCode;
import com.hrsecurity.crypto.FieldHashUtil;
import com.hrsecurity.dto.BackfillResult;
import com.hrsecurity.entity.LegacyEmployeePlain;
import com.hrsecurity.entity.SysEmployee;
import com.hrsecurity.mapper.LegacyEmployeePlainMapper;
import com.hrsecurity.mapper.SysEmployeeMapper;
import com.hrsecurity.service.CryptoMigrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 历史数据加密迁移实现（幂等）。
 *
 * 流程：读 legacy_employee_plain 的明文 → 按工号找 sys_employee → 明文写进实体的敏感字段
 * （TypeHandler 加密落库）→ 同时刷新 id_card_hash。
 *
 * 幂等怎么保证（面试点）：判断依据是"目标记录是否已有敏感数据"（SysEmployee#hasSensitiveData），
 * 有就跳过。不能用"密文是否相同"判断——每次加密 IV 随机，密文必然不同。
 *
 * 注意：迁移不是"改数据库里的值"，而是"用同一套应用侧加密逻辑重写"，
 * 这样密文格式、keyId、哈希算法只有一个实现，不会出现"脚本用的算法与程序不一致"的经典事故。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoMigrationServiceImpl implements CryptoMigrationService {

    private final LegacyEmployeePlainMapper legacyMapper;
    private final SysEmployeeMapper employeeMapper;
    private final FieldHashUtil fieldHashUtil;

    @Override
    @Transactional
    public BackfillResult backfill() {
        List<LegacyEmployeePlain> legacyRows = loadLegacyRows();
        int migrated = 0;
        int skipped = 0;
        int unmatched = 0;
        for (LegacyEmployeePlain row : legacyRows) {
            SysEmployee employee = employeeMapper.selectOne(
                    new LambdaQueryWrapper<SysEmployee>().eq(SysEmployee::getEmpNo, row.getEmpNo()));
            if (employee == null) {
                // 旧系统有、新系统没有：多半已经离职被逻辑删除，或工号对不上，交给人核对
                unmatched++;
                log.warn("迁移跳过：旧系统工号 {} 在员工表中无对应记录", row.getEmpNo());
                continue;
            }
            if (employee.hasSensitiveData()) {
                skipped++;
                continue;
            }
            boolean changed = false;
            if (StringUtils.hasText(row.getIdCard())) {
                employee.setIdCardEnc(row.getIdCard());
                employee.setIdCardHash(fieldHashUtil.hmacSha256Hex(row.getIdCard()));
                changed = true;
            }
            if (StringUtils.hasText(row.getPhone())) {
                employee.setPhoneEnc(row.getPhone());
                changed = true;
            }
            if (StringUtils.hasText(row.getBankCard())) {
                employee.setBankCardEnc(row.getBankCard());
                changed = true;
            }
            if (StringUtils.hasText(row.getSalary())) {
                employee.setSalaryEnc(row.getSalary());
                changed = true;
            }
            if (!changed) {
                skipped++;
                continue;
            }
            // updateById 走同一套 TypeHandler：实体里是明文，落库自动变密文
            employeeMapper.updateById(employee);
            migrated++;
        }
        BackfillResult result = new BackfillResult(legacyRows.size(), migrated, skipped, unmatched);
        log.info("历史数据加密迁移完成：{}", result);
        return result;
    }

    /** 遗留明文表可能已被运维 DROP（迁移完成后本就该清掉），这里给出可读提示而不是 500 */
    private List<LegacyEmployeePlain> loadLegacyRows() {
        try {
            return legacyMapper.selectList(null);
        } catch (DataAccessException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "旧系统明文表 legacy_employee_plain 不存在或不可读，可能已迁移完成并清理");
        }
    }
}
