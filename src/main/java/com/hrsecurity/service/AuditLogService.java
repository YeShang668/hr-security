package com.hrsecurity.service;

import com.hrsecurity.common.PageResult;
import com.hrsecurity.dto.AuditLogQuery;
import com.hrsecurity.dto.AuditLogVO;

/** 审计日志查询（只有读；写入走切面 → AuditLogRecorder 异步链路） */
public interface AuditLogService {

    PageResult<AuditLogVO> page(AuditLogQuery query);
}
