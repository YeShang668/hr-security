package com.hrsecurity.controller;

import com.hrsecurity.common.Result;
import com.hrsecurity.dto.BackfillResult;
import com.hrsecurity.service.CryptoMigrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 加密运维接口（仅 ADMIN）。
 *
 * 目前只有"历史数据加密迁移"一个动作。设计取舍记在这里（面试可能问）：
 * 生产环境更推荐做成独立的一次性 Job / CLI（不进 Web 层，不占应用权限模型）；
 * 本项目放在 ADMIN 接口里，是为了在答辩演示与自动化回归里能一键触发、并且幂等可重复跑。
 * 无论哪种形式，都必须满足三点：仅管理员可执行、幂等、留下执行报告。
 */
@RestController
@RequestMapping("/api/admin/crypto")
@RequiredArgsConstructor
public class CryptoAdminController {

    private final CryptoMigrationService cryptoMigrationService;

    /**
     * 旧系统明文 → 加密迁移（幂等，可重复执行）。
     * POST /api/admin/crypto/backfill
     */
    @PostMapping("/backfill")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<BackfillResult> backfill() {
        return Result.success("加密迁移执行完成", cryptoMigrationService.backfill());
    }
}
