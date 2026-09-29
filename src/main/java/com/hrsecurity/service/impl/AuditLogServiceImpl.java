package com.hrsecurity.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hrsecurity.audit.AuditLog;
import com.hrsecurity.common.PageResult;
import com.hrsecurity.dto.AuditLogQuery;
import com.hrsecurity.dto.AuditLogVO;
import com.hrsecurity.entity.SysAuditLog;
import com.hrsecurity.mapper.SysAuditLogMapper;
import com.hrsecurity.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 审计日志查询实现。
 *
 * 注意本类自己也被审计埋点（方法上的 @AuditLog）：**查询审计日志本身也是敏感行为**——
 * 谁能翻看别人的操作记录，在安全审计里同样是要留痕的事
 * （否则"内鬼先把审计日志翻一遍"就成了盲区）。代价是每次查询多一行审计记录，可接受。
 *
 * 查询限流/权限：Controller 上仅 ADMIN；分页必须带 page/size 上限（见 Controller 注释），
 * 避免有人用 size=1000000 把审计表整表导出。
 */
@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private final SysAuditLogMapper auditLogMapper;

    @AuditLog(operation = "查询审计日志", targetType = "AUDIT_LOG",
            detail = "审计查询行为本身同样留痕，避免翻账不留痕")
    @Override
    public PageResult<AuditLogVO> page(AuditLogQuery query) {
        LambdaQueryWrapper<SysAuditLog> wrapper = new LambdaQueryWrapper<>();
        if (query.getUserId() != null) {
            wrapper.eq(SysAuditLog::getUserId, query.getUserId());
        }
        if (StringUtils.hasText(query.getUsername())) {
            wrapper.like(SysAuditLog::getUsername, query.getUsername());
        }
        if (StringUtils.hasText(query.getOperation())) {
            wrapper.like(SysAuditLog::getOperation, query.getOperation());
        }
        if (StringUtils.hasText(query.getTargetType())) {
            wrapper.eq(SysAuditLog::getTargetType, query.getTargetType());
        }
        if (StringUtils.hasText(query.getResult())) {
            wrapper.eq(SysAuditLog::getResult, query.getResult());
        }
        if (query.getStartTime() != null) {
            wrapper.ge(SysAuditLog::getCreatedAt, query.getStartTime());
        }
        if (query.getEndTime() != null) {
            wrapper.le(SysAuditLog::getCreatedAt, query.getEndTime());
        }
        // 按 id 倒序而不是按时间倒序：id 是自增主键，排序无并列歧义（时间可能同秒）
        wrapper.orderByDesc(SysAuditLog::getId);

        Page<SysAuditLog> page = auditLogMapper.selectPage(new Page<>(query.getPage(), query.getSize()), wrapper);
        List<AuditLogVO> records = page.getRecords().stream().map(this::toVO).collect(Collectors.toList());
        return new PageResult<>(page.getTotal(), records);
    }

    private AuditLogVO toVO(SysAuditLog e) {
        return AuditLogVO.builder()
                .id(e.getId())
                .userId(e.getUserId())
                .username(e.getUsername())
                .operation(e.getOperation())
                .targetType(e.getTargetType())
                .targetId(e.getTargetId())
                .detail(e.getDetail())
                .result(e.getResult())
                .ip(e.getIp())
                .userAgent(e.getUserAgent())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
