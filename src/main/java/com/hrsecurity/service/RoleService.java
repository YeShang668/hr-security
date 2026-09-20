package com.hrsecurity.service;

import com.hrsecurity.entity.SysRole;

import java.util.List;

/**
 * 用户角色 / 权限查询（带 Redis 缓存 user:roles:{userId}、user:perms:{userId}）。
 * 角色与权限是鉴权判断的依据，登录、/me、每次请求的过滤器都要用，
 * 缓存命中时不再查库，变更后调用 evict 删缓存即可即时生效。
 */
public interface RoleService {

    /** 查用户角色编码：缓存优先，未命中查库并回填 */
    List<String> getRoleCodes(Long userId);

    /**
     * 查用户的**权限编码**（角色 → 角色权限 → 权限编码，第 6 周新增）。
     * 角色回答"你是谁"，权限编码回答"你能做什么"：像"能否看到敏感字段明文"这类
     * 细粒度控制，用权限编码表达比继续堆角色名清晰，以后加减权限也不用改代码。
     * 同样走 Redis 缓存（user:perms:{userId}）。
     */
    List<String> getPermissionCodes(Long userId);

    /** 角色/权限变更后调用：删除该用户的角色缓存与权限缓存，下一次请求查库回填 */
    void evict(Long userId);

    /** 全部启用角色，供用户管理页的角色下拉框使用 */
    List<SysRole> listAll();
}
