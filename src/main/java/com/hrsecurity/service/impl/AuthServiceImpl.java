package com.hrsecurity.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

    public AuthServiceImpl(SysUserMapper userMapper, SysRoleMapper roleMapper,
                           SysUserRoleMapper userRoleMapper, PasswordEncoder passwordEncoder,
                           JwtUtil jwtUtil, SessionService sessionService, RoleService roleService) {
        super(userMapper);
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.sessionService = sessionService;
        this.roleService = roleService;
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

    @Override
    public LoginResponse login(LoginDTO dto) {
        // 1. 按用户名查用户
        SysUser user = baseMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername()));
        // 2. 用户不存在和密码错误返回同一提示，避免暴露"用户名是否注册"
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "用户名或密码错误");
        }
        // 3. 账号状态检查
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "账号已被禁用，请联系管理员");
        }

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
