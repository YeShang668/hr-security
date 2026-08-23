package com.hrsecurity.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 返回给前端的用户信息（不含密码）。
 * 实体 SysUser 禁止直接出参，一律转成该 VO。
 */
@Data
@Builder
public class UserInfoVO {

    private Long id;
    private String username;
    private String nickname;

    /** 角色编码列表，如 ["ADMIN"]，前端据此渲染菜单/按钮 */
    private List<String> roles;

    /** LocalDateTime 不受 spring.jackson.date-format 控制，需单独指定格式 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
