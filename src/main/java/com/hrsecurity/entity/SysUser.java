package com.hrsecurity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表实体，对应 sys_user。
 * 注意：实体只做数据库映射，绝不直接返回给前端（密码会漏出去）。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名，唯一 */
    private String username;

    /** BCrypt 密文，不是明文 */
    private String password;

    private String nickname;

    /** 1启用 0禁用 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
