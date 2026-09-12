package com.hrsecurity.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 角色分配请求参数：整体覆盖式分配（前端多选提交最终结果）。
 * 必须至少一个角色——无角色用户登录后看不到任何菜单，属于"锁死"状态，
 * 因此在校验层直接拦住（也避免管理员误操作产生孤儿权限）。
 */
@Data
public class UserRolesDTO {

    @NotEmpty(message = "至少分配一个角色")
    private List<Long> roleIds;
}
