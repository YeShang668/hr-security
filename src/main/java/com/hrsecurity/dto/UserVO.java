package com.hrsecurity.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户管理列表出参。
 * 与 UserInfoVO 的区别：这里是"管理员看别人"的视角，带账号状态与角色编码，
 * 同样不含密码字段（SysUser 实体禁止直接出参）。
 */
@Data
@Builder
public class UserVO {

    private Long id;

    private String username;

    private String nickname;

    /** 1启用 0禁用 */
    private Integer status;

    /** 角色编码列表，如 ["ADMIN"]、["EMPLOYEE"]；一个用户都没有角色的边界情况返回空数组 */
    private List<String> roles;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
