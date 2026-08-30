package com.hrsecurity.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 员工表实体，对应 sys_employee。
 *
 * 红线：敏感字段（身份证/工资等）本周未建列，9 月 AES 加密周再 ALTER 添加，避免明文落库。
 * 逻辑删除：status 字段兼任"在职/离职"标记（1=在职未删，0=离职已删）。
 * 用实体级 @TableLogic 而非全局 logic-delete-field 配置，避免误伤
 * sys_user/sys_role/sys_permission 的 status=0"禁用"语义（禁用≠删除）。
 */
@Data
@TableName("sys_employee")
public class SysEmployee {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工号，唯一 */
    private String empNo;

    private String name;

    /** 1男 2女 */
    private Integer gender;

    /** 手机号占位列，9 月加密 */
    private String phone;

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
}
