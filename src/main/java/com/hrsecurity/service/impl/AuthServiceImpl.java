package com.hrsecurity.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hrsecurity.common.BusinessException;
import com.hrsecurity.common.ResultCode;
import com.hrsecurity.dto.LoginDTO;
import com.hrsecurity.dto.LoginResponse;
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
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /** 注册用户的默认角色（由种子数据保证存在） */
    private static final String DEFAULT_ROLE = "EMPLOYEE";

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Override
    @Transactional
    public void register(RegisterDTO dto) {
        // 1. 用户名唯一校验（数据库层面也有 UNIQUE 索引兜底）
        Long count = userMapper.selectCount(
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
        userMapper.insert(user);

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
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername()));
        // 2. 用户不存在和密码错误返回同一提示，避免暴露"用户名是否注册"
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "用户名或密码错误");
        }
        // 3. 账号状态检查
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "账号已被禁用，请联系管理员");
        }

        // 4. 签发 JWT（角色写进 claims），返回 token + 用户信息 + 角色
        List<String> roles = getRoleCodes(user.getId());
        String token = jwtUtil.createToken(user.getId(), user.getUsername(), roles);
        return LoginResponse.builder()
                .token(token)
                .user(toUserInfo(user, roles))
                .roles(roles)
                .build();
    }

    @Override
    public UserInfoVO getUserInfo(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        return toUserInfo(user, getRoleCodes(userId));
    }

    /** 查用户绑定的角色编码列表 */
    private List<String> getRoleCodes(Long userId) {
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
