package com.hrsecurity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hrsecurity.entity.SysAuditLog;

/**
 * 审计日志 Mapper：只有 insert（切面写）与 select（审计页查），
 * 刻意不提供 update/delete —— 审计记录不可被应用代码篡改。
 */
public interface SysAuditLogMapper extends BaseMapper<SysAuditLog> {
}
