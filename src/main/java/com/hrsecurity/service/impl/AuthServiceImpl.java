package com.hrsecurity.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hrsecurity.audit.AuditLog;
import com.hrsecurity.audit.AuditTrace;
import com.hrsecurity.common.BusinessException;
import com.hrsecurity.common.ResultCode;
import com.hrsecurity.dto.LoginDTO;
import com.hrsecurity.dto.LoginResponse;
import com.hrsecurity.dto.LoginSession;
import com.hrsecurity.dto.RegisterDTO;
import com.hrsecurity.dto.UserInfoVO;
import com.hrsecurity.entity.SysRole;
import com.hrsecurity.entity.SysUser;
import com.hrsecurity.entity.SysUserRole;
import com.hrsecurity.mapper.SysRoleMapper;
import com.hrsecurity.mapper.SysUserMapper;
import com.hrsecurity.mapper.SysUserRoleMapper;
import com.hrsecurity.security.JwtUtil;
import com.hrsecurity.security.LoginAttemptService;
import com.hrsecurity.service.AuthService;
import com.hrsecurity.service.RoleService;
import com.hrsecurity.service.SessionService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AuthServiceImpl extends BaseServiceImpl<SysUserMapper, SysUser> implements AuthService {

    /** 注册用户的默认角色（由种子数据保证存在） */
    private static final String DEFAULT_ROLE = "EMPLOYEE";

    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final SessionService sessionService;
    private final RoleService roleService;
    private final LoginAttemptService loginAttemptService;

    public AuthServiceImpl(SysUserMapper userMapper, SysRoleMapper roleMapper,
                           SysUserRoleMapper userRoleMapper, PasswordEncoder passwordEncoder,
                           JwtUtil jwtUtil, SessionService sessionService, RoleService roleService,
                           LoginAttemptService loginAttemptService) {
        super(userMapper);
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.sessionService = sessionService;
        this.roleService = roleService;
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    @Transactional
    public void register(RegisterDTO dto) {
        // 1. 用户名唯一校验（数据库层面也有 UNIQUE 索引兜底）
        Long count = baseMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername()));
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "用户名已存在");
        }

        // 2. BCrypt 加密：库里永远只存密文
        SysUser user = new SysUser();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname());
        user.setStatus(1);
        baseMapper.insert(user);

        // 3. 默认分配 EMPLOYEE 角色（普通注册用户没有管理员权限）
        SysRole defaultRole = roleMapper.selectOne(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, DEFAULT_ROLE));
        if (defaultRole == null) {
            throw new BusinessException(ResultCode.ERROR.getCode(), "系统默认角色未配置，请联系管理员");
        }
        SysUserRole userRole = new SysUserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(defaultRole.getId());
        userRoleMapper.insert(userRole);
    }

    /**
     * 登录。
     *
     * 第 8 周加固三点（都在这个方法里，顺序不能换）：
     * 1. **先查锁定**再校验密码：锁定期间的"正确密码"同样拒绝，否则锁定形同虚设；
     * 2. 凭证错误时**立刻计数**：账号维度 5 次 / IP 维度 20 次（阈值差异的理由见 LoginAttemptService）；
     * 3. **登录本身进审计**（@AuditLog + AuditTrace）：成功与失败都留痕，
     *    失败记录只写"尝试了哪个账号**，绝不写口令**（否则审计表变成口令字典）。
     */
    @AuditLog(operation = "用户登录", targetType = "USER", detail = "登录留痕：成功/失败均记录，不记口令")
    @Override
    public LoginResponse login(LoginDTO dto) {
        // 0. 限流前置检查：已锁定则直接 429，不进入下面的密码比对
        loginAttemptService.assertNotLocked(dto.getUsername());

        // 1. 按用户名查用户
        SysUser user = baseMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername()));
        // 2. 用户不存在和密码错误返回同一提示，避免暴露"用户名是否注册"
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            AuditTrace.append("尝试登录账号：" + dto.getUsername());
            loginAttemptService.onFailure(dto.getUsername());
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "用户名或密码错误");
        }
        // 3. 账号状态检查
        if (user.getStatus() != null && user.getStatus() == 0) {
            AuditTrace.append("账号已禁用：" + dto.getUsername());
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "账号已被禁用，请联系管理员");
        }

        // 3.5 凭证正确才清账号失败计数：避免"正常用户白天错 4 次、晚上再错 1 次就被锁"
        //     （IP 计数不清，撞库行为仍然会累积到 IP 维度上）
        loginAttemptService.onSuccess(dto.getUsername());
        AuditTrace.append("登录成功：" + dto.getUsername());

        // 4. 角色：先删缓存再取（未命中查库回填），保证登录拿到的角色是最新的
        roleService.evict(user.getId());
        List<String> roles = roleService.getRoleCodes(user.getId());

        // 5. 签发 JWT（claims 里的角色仅作兼容，权限判断以 Redis 会话 + 角色缓存为准）
        String token = jwtUtil.createToken(user.getId(), user.getUsername(), roles);
        // 6. 写 Redis 会话：TTL 与 JWT 一致，登出/删除会话 = 旧 token 立即失效
        sessionService.createSession(token, new LoginSession(user.getId(), user.getUsername()),
                jwtUtil.getExpireSeconds());
        return LoginResponse.builder()
                .token(token)
                .user(toUserInfo(user, roles))
                .roles(roles)
                .build();
    }

    @Override
    public UserInfoVO getUserInfo(Long userId) {
        SysUser user = getOrThrow(userId, "用户不存在");
        return toUserInfo(user, roleService.getRoleCodes(userId));
    }

    @Override
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        // 先删角色缓存再删会话（都是删，顺序不影响一致性，但先清权限可避免残留权限判断）
        Long uid = jwtUtil.getUserId(token);
        if (uid != null) {
            roleService.evict(uid);
        }
        sessionService.removeSession(token);
    }

    /** 实体 → 出参 VO，杜绝密码泄露 */
    private UserInfoVO toUserInfo(SysUser user, List<String> roles) {
        return UserInfoVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .roles(roles)
                .createdAt(user.getCreatedAt())
                .build();
    }
}
