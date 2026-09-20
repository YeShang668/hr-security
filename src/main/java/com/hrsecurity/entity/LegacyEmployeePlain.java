package com.hrsecurity.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 旧系统明文员工表（模拟），对应 legacy_employee_plain——**只是加密迁移的数据源**。
 *
 * 真实场景里它可能是老系统的一张表、一个 CSV 导出或备份文件；
 * 迁移程序读它的明文，加密后写入 sys_employee 的密文列。
 * 本实体没有任何 @TableField(typeHandler)，因为它存的本来就是明文，不需要解密。
 */
@Data
@TableName("legacy_employee_plain")
public class LegacyEmployeePlain {

    /** 工号：与 sys_employee.emp_no 对应（迁移的关联键） */
    @TableId
    private String empNo;

    /** 身份证明文（迁移源） */
    private String idCard;

    /** 手机号明文（迁移源） */
    private String phone;

    /** 银行卡明文（迁移源） */
    private String bankCard;

    /** 工资明文（迁移源） */
    private String salary;
}
