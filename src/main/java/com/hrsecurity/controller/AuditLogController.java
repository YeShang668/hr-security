package com.hrsecurity.controller;

import com.hrsecurity.common.PageResult;
import com.hrsecurity.common.Result;
import com.hrsecurity.dto.AuditLogQuery;
import com.hrsecurity.dto.AuditLogVO;
import com.hrsecurity.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计日志查询接口（仅 ADMIN）。
 *
 * 权限口径与 /api/users 一致：审计记录能看到"谁看了谁的敏感信息"，
 * 本身就是高敏数据，普通员工连列表都不给看（EMPLOYEE → 403，未登录 → 401）。
 *
 * 分页上限（size ≤ 100）是刻意加的：审计表是全库增长最快的表，
 * 不设上限的话一个 size=100000 的请求就能把整表拉走，既拖库又等于批量泄露。
 */
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    /** 单页最大条数：防止有人把审计表整表导出 */
    private static final long MAX_SIZE = 100;

    private final AuditLogService auditLogService;

    /**
     * 审计日志分页查询：
     * GET /api/audit-logs?page=1&size=10&username=admin&operation=敏感
     *                     &result=FAILURE&startTime=2026-09-26 00:00:00
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<PageResult<AuditLogVO>> page(AuditLogQuery query) {
        if (query.getSize() > MAX_SIZE) {
            query.setSize(MAX_SIZE);
        }
        if (query.getSize() <= 0) {
            query.setSize(10);
        }
        if (query.getPage() <= 0) {
            query.setPage(1);
        }
        return Result.success(auditLogService.page(query));
    }
}
