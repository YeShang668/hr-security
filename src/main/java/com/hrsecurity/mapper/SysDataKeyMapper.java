package com.hrsecurity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hrsecurity.entity.SysDataKey;

/**
 * DEK 表 Mapper。
 * 写入只发生在两处：启动引导（首次生成 k1）、密钥轮换/停用（管理员显式操作）。
 */
public interface SysDataKeyMapper extends BaseMapper<SysDataKey> {
}
