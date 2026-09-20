package com.hrsecurity.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hrsecurity.crypto.AesTypeHandler;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 员工表实体，对应 sys_employee。
 *
 * 第 6 周改造：敏感字段（手机号/身份证/银行卡/工资）变成"实体里是明文、数据库里是密文"——
 * 靠 {@code @TableField(typeHandler = AesTypeHandler.class)} 在读写时自动加解密，
 * 业务代码看到的始终是明文，落库的是 v1:{keyId}:{iv}:{ct}。
 *
 * 红线：明文列已全部删除，不允许"密文 + 明文"并存一份（否则加密形同虚设）。
 *
 * 逻辑删除：status 字段兼任"在职/离职"标记（1=在职未删，0=离职已删）。
 * 用实体级 @TableLogic 而非全局 logic-delete-field 配置，避免误伤
 * sys_user/sys_role/sys_permission 的 status=0"禁用"语义（禁用≠删除）。
 *
 * 坑记录（BUG6-1）：{@code autoResultMap = true} 必须加。MyBatis-Plus 默认的实体结果映射
 * **不带**自定义 TypeHandler，只在写入方向生效——漏加的表现是"写进去是密文、读出来也是密文"，
 * 业务层拿到一长串 v1:k1:... 还以为是数据问题。
 */
@Data
@TableName(value = "sys_employee", autoResultMap = true)
public class SysEmployee {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工号，唯一（非敏感：用于对内检索与展示） */
    private String empNo;

    private String name;

    /** 1男 2女 */
    private Integer gender;

    /** 手机号（进内存是明文，落库自动加密） */
    @TableField(value = "phone_enc", typeHandler = AesTypeHandler.class)
    private String phoneEnc;

    /** 身份证号（进内存是明文，落库自动加密） */
    @TableField(value = "id_card_enc", typeHandler = AesTypeHandler.class)
    private String idCardEnc;

    /** 银行卡号（进内存是明文，落库自动加密） */
    @TableField(value = "bank_card_enc", typeHandler = AesTypeHandler.class)
    private String bankCardEnc;

    /** 工资（进内存是明文，落库自动加密） */
    @TableField(value = "salary_enc", typeHandler = AesTypeHandler.class)
    private String salaryEnc;

    /**
     * 身份证 HMAC-SHA256 哈希（不可逆），只用于"按身份证精确查"与"身份证唯一校验"。
     * 不加密的原因：它本来就是单向摘要，加密之后就没法做等值匹配了。
     */
    private String idCardHash;

    private String email;

    private Long deptId;

    /** 入职日期 */
    private LocalDate entryDate;

    /** 逻辑删除位：1在职(未删) 0离职(已删) */
    @TableLogic(value = "1", delval = "0")
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 是否已有敏感字段值（历史数据迁移的幂等判断依据：有值就不再刷） */
    public boolean hasSensitiveData() {
        return notEmpty(idCardEnc) || notEmpty(phoneEnc) || notEmpty(bankCardEnc) || notEmpty(salaryEnc);
    }

    private boolean notEmpty(String value) {
        return value != null && !value.isEmpty();
    }
}
