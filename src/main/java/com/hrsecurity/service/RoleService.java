package com.hrsecurity.service;

import java.util.List;

/**
 * 用户角色查询（带 Redis 缓存 user:roles:{userId}）。
 * 角色是权限判断的依据，登录、/me、每次请求的过滤器都要用，
 * 缓存命中时不再查库，角色变更后调用 evict 删缓存即可即时生效。
 */
public interface RoleService {

    /** 查用户角色编码：缓存优先，未命中查库并回填 */
    List<String> getRoleCodes(Long userId);

    /** 角色变更后调用：删除该用户角色缓存，下一次请求查库回填新角色 */
    void evict(Long userId);
}
