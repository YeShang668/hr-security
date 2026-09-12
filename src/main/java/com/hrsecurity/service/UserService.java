package com.hrsecurity.service;

import com.hrsecurity.common.PageResult;
import com.hrsecurity.dto.UserVO;

import java.util.List;

/**
 * 用户管理（管理员视角）：列表 / 启用禁用 / 角色分配。
 * 本周新增——前端"用户管理"页需要后端接口，原 RBAC 只做了登录侧的角色读取。
 */
public interface UserService {

    /** 用户分页列表：keyword 模糊匹配用户名或昵称，status 可选过滤 */
    PageResult<UserVO> page(long pageNum, long pageSize, String keyword, Integer status);

    /**
     * 启用/禁用账号。
     * 禁用时必须让该用户已登录的 token 立即失效（删 Redis 会话），
     * 否则"禁用"只是一个标记，旧 token 24 小时内仍能访问——等于没禁用。
     */
    void updateStatus(Long id, Integer status, Long currentUserId);

    /** 覆盖式分配角色：先删旧关系再插新关系，并删角色缓存使其下一次请求即时生效 */
    void assignRoles(Long id, List<Long> roleIds, Long currentUserId);
}
