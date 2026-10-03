package com.hrsecurity.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hrsecurity.audit.AuditLog;
import com.hrsecurity.audit.AuditTrace;
import com.hrsecurity.common.BusinessException;
import com.hrsecurity.common.LikeUtils;
import com.hrsecurity.common.PageResult;
import com.hrsecurity.common.ResultCode;
import com.hrsecurity.dto.UserVO;
import com.hrsecurity.entity.SysRole;
import com.hrsecurity.entity.SysUser;
import com.hrsecurity.entity.SysUserRole;
import com.hrsecurity.mapper.SysRoleMapper;
import com.hrsecurity.mapper.SysUserMapper;
import com.hrsecurity.mapper.SysUserRoleMapper;
import com.hrsecurity.service.RoleService;
import com.hrsecurity.service.SessionService;
import com.hrsecurity.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 用户管理实现。三个"安全红线"值得记：
 * 1. 列表出参一律走 UserVO，密码永远不出库；
 * 2. 管理员不能禁用/改自己的角色，避免把自己锁在系统外；
 * 3. 禁用账号必须同时删 Redis 会话（踢下线）+ 删角色缓存，否则旧 token 还能用。
 */
@Service
public class UserServiceImpl extends BaseServiceImpl<SysUserMapper, SysUser> implements UserService {

    /** 内置管理员用户名：种子数据创建，禁用它可能导致系统无人可管理 */
    private static final String BUILTIN_ADMIN = "admin";

    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final RoleService roleService;
    private final SessionService sessionService;

    public UserServiceImpl(SysUserMapper userMapper, SysUserRoleMapper userRoleMapper,
                           SysRoleMapper roleMapper, RoleService roleService,
                           SessionService sessionService) {
        super(userMapper);
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.roleService = roleService;
        this.sessionService = sessionService;
    }

    @Override
    public PageResult<UserVO> page(long pageNum, long pageSize, String keyword, Integer status) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            // 同 EmployeeServiceImpl：keyword 里的 % _ 是 LIKE 通配符，参数化拦不住，必须转义
            String safeKeyword = LikeUtils.escape(keyword);
            wrapper.and(w -> w.like(SysUser::getUsername, safeKeyword)
                    .or()
                    .like(SysUser::getNickname, safeKeyword));
        }
        if (status != null) {
            wrapper.eq(SysUser::getStatus, status);
        }
        wrapper.orderByAsc(SysUser::getId);

        Page<SysUser> page = baseMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        Map<Long, List<String>> roleMap = roleCodeMap(
                page.getRecords().stream().map(SysUser::getId).collect(Collectors.toList()));
        List<UserVO> records = page.getRecords().stream()
                .map(u -> toVO(u, roleMap.getOrDefault(u.getId(), Collections.emptyList())))
                .collect(Collectors.toList());
        return new PageResult<>(page.getTotal(), records);
    }

    /**
     * 启用/禁用账号。第 7 周加审计：**权限变更本身就是审计对象**——
     * "谁把谁的账号禁用了"是事后追责里最常被问的一句话。
     * 注意审计失败也要记：比如"试图禁用自己"被红线拦下，那条 FAILURE 记录同样有价值。
     */
    @AuditLog(operation = "变更账号状态", targetType = "USER", targetIdArgIndex = 0,
            detail = "禁用会同时删除该用户全部登录会话与权限缓存（踢下线）")
    @Override
    @Transactional
    public void updateStatus(Long id, Integer status, Long currentUserId) {
        SysUser user = getOrThrow(id, "用户不存在");
        if (status == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "状态不能为空");
        }
        // 红线 1：不能禁用自己（管理员点错了就把自己踢出去了）
        if (status == 0 && Objects.equals(id, currentUserId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "不能禁用当前登录账号");
        }
        // 红线 2：内置 admin 账号保留，防止"最后一个管理员被禁用"导致无法管理
        if (status == 0 && BUILTIN_ADMIN.equals(user.getUsername())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "内置管理员账号不可禁用");
        }
        user.setStatus(status);
        baseMapper.updateById(user);

        if (status == 0) {
            // 红线 3：禁用要"生效"——删掉该用户所有登录会话 + 角色缓存，
            // 他手里的旧 token 下一次请求就是 401，不用等 24 小时
            sessionService.removeAllSessions(id);
            roleService.evict(id);
        }
        AuditTrace.append("账号 " + user.getUsername() + " 状态改为 " + (status == 1 ? "启用(1)" : "禁用(0)"));
    }

    /**
     * 覆盖式分配角色。审计记录"谁给谁分配了哪些角色"——
     * 提权（给普通账号加 ADMIN）是最需要留痕的一类操作。
     */
    @AuditLog(operation = "分配用户角色", targetType = "USER", targetIdArgIndex = 0,
            detail = "覆盖式分配，改完旧 token 立即生效（已删角色与权限缓存）")
    @Override
    @Transactional
    public void assignRoles(Long id, List<Long> roleIds, Long currentUserId) {
        SysUser user = getOrThrow(id, "用户不存在");
        // 不能改自己的角色：防止管理员把自己降成 EMPLOYEE 后再也回不来
        if (Objects.equals(id, currentUserId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "不能修改当前登录账号的角色");
        }
        List<Long> distinctRoleIds = roleIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (distinctRoleIds.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "至少分配一个角色");
        }
        List<SysRole> roles = roleMapper.selectBatchIds(distinctRoleIds);
        if (roles.size() != distinctRoleIds.size()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "角色不存在");
        }

        // 覆盖式分配：先清旧关系再插新关系（同事务，避免出现"无角色"的中间态）
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
        for (Long roleId : distinctRoleIds) {
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(id);
            userRole.setRoleId(roleId);
            userRoleMapper.insert(userRole);
        }
        // 删角色缓存：该用户下一次请求就拿到新角色（旧 token 立即生效，无需重登）
        roleService.evict(id);
        AuditTrace.append("账号 " + user.getUsername() + " 角色：" + roles.stream()
                .map(SysRole::getRoleCode).sorted().collect(Collectors.joining("/")));
    }

    /**
     * 一次查全当前页用户的角色，避免逐条查库/查缓存的 N+1（每页 10 条就是 10 次往返）。
     * 这里刻意不走 RoleService 缓存：缓存是给"每次请求鉴权"用的单用户查询，
     * 列表页做批量查询更省事，缓存该由发号施令的那次鉴权去暖。
     */
    private Map<Long, List<String>> roleCodeMap(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<SysUserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().in(SysUserRole::getUserId, userIds));
        if (userRoles.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> roleCodes = roleMapper.selectBatchIds(
                        userRoles.stream().map(SysUserRole::getRoleId).distinct().collect(Collectors.toList()))
                .stream().collect(Collectors.toMap(SysRole::getId, SysRole::getRoleCode, (a, b) -> a));
        return userRoles.stream()
                .filter(ur -> roleCodes.containsKey(ur.getRoleId()))
                .collect(Collectors.groupingBy(SysUserRole::getUserId,
                        Collectors.mapping(ur -> roleCodes.get(ur.getRoleId()), Collectors.toList())));
    }

    private UserVO toVO(SysUser user, List<String> roles) {
        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .status(user.getStatus())
                .roles(roles)
                .createdAt(user.getCreatedAt())
                .build();
    }
}
