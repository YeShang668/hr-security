package com.hrsecurity.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hrsecurity.entity.SysPermission;
import com.hrsecurity.entity.SysRole;
import com.hrsecurity.entity.SysRolePermission;
import com.hrsecurity.entity.SysUserRole;
import com.hrsecurity.mapper.SysPermissionMapper;
import com.hrsecurity.mapper.SysRoleMapper;
import com.hrsecurity.mapper.SysRolePermissionMapper;
import com.hrsecurity.mapper.SysUserRoleMapper;
import com.hrsecurity.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 角色 / 权限缓存实现：user:roles:{userId}、user:perms:{userId}。
 *
 * 缓存一致性（面试点）：角色变更必须"先删缓存"，下一次请求查库回填新角色，
 * 保证变更即时生效；缓存 TTL 30 分钟作为兜底——即使漏删，也最多 30 分钟后自动恢复一致。
 */
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    /** 角色缓存 key：user:roles:{userId} */
    private static final String ROLE_CACHE_KEY_PREFIX = "user:roles:";

    /** 权限编码缓存 key：user:perms:{userId}（第 6 周新增） */
    private static final String PERM_CACHE_KEY_PREFIX = "user:perms:";

    /** 缓存兜底 TTL：变更后漏删缓存时，最多 30 分钟自动恢复一致 */
    private static final long ROLE_CACHE_TTL_SECONDS = 30 * 60;

    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysPermissionMapper permissionMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public List<String> getRoleCodes(Long userId) {
        String key = ROLE_CACHE_KEY_PREFIX + userId;
        // 1. 缓存命中直接返回，避免每次请求查库
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached instanceof List<?> list && !list.isEmpty()) {
            return toStringList(list);
        }
        // 2. 未命中（首次/变更后）：查库并回填缓存
        List<String> roles = loadFromDb(userId);
        redisTemplate.opsForValue().set(key, roles, Duration.ofSeconds(ROLE_CACHE_TTL_SECONDS));
        return roles;
    }

    @Override
    public List<String> getPermissionCodes(Long userId) {
        String key = PERM_CACHE_KEY_PREFIX + userId;
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached instanceof List<?> list && !list.isEmpty()) {
            return toStringList(list);
        }
        List<String> perms = loadPermsFromDb(userId);
        redisTemplate.opsForValue().set(key, perms, Duration.ofSeconds(ROLE_CACHE_TTL_SECONDS));
        return perms;
    }

    @Override
    public void evict(Long userId) {
        // 角色与权限两份缓存一起删：只删角色的话，权限编码缓存会残留 30 分钟，
        // 出现"角色已经改了、敏感明文权限却还在"的窗口期
        redisTemplate.delete(List.of(ROLE_CACHE_KEY_PREFIX + userId, PERM_CACHE_KEY_PREFIX + userId));
    }

    @Override
    public List<SysRole> listAll() {
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getStatus, 1)
                .orderByAsc(SysRole::getId));
    }

    /** 从数据库查用户绑定的角色编码列表（sys_user_role → sys_role 两表） */
    private List<String> loadFromDb(Long userId) {
        List<Long> roleIds = userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).collect(Collectors.toList());
        if (roleIds.isEmpty()) {
            return Collections.emptyList();
        }
        return roleMapper.selectBatchIds(roleIds).stream()
                .map(SysRole::getRoleCode)
                .collect(Collectors.toList());
    }

    /** Redis 反序列化回来的 List 元素可能是 LinkedHashMap/String，统一转成 String */
    private List<String> toStringList(List<?> list) {
        List<String> roles = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o != null) {
                roles.add(o.toString());
            }
        }
        return roles;
    }

    /**
     * 从数据库查用户权限编码：sys_user_role → sys_role_permission → sys_permission。
     * 只取 status=1 的启用权限；同一次查询里用批量 in 查询，避免三条 SQL 循环查。
     */
    private List<String> loadPermsFromDb(Long userId) {
        List<Long> roleIds = userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).collect(Collectors.toList());
        if (roleIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> permIds = rolePermissionMapper.selectList(
                        new LambdaQueryWrapper<SysRolePermission>().in(SysRolePermission::getRoleId, roleIds))
                .stream().map(SysRolePermission::getPermissionId).distinct().collect(Collectors.toList());
        if (permIds.isEmpty()) {
            return Collections.emptyList();
        }
        return permissionMapper.selectBatchIds(permIds).stream()
                .filter(p -> p.getStatus() == null || p.getStatus() == 1)
                .map(SysPermission::getPermCode)
                .distinct()
                .collect(Collectors.toList());
    }
}
