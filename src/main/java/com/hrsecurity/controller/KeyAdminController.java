package com.hrsecurity.controller;

import com.hrsecurity.common.Result;
import com.hrsecurity.dto.DataKeyVO;
import com.hrsecurity.dto.ReencryptResult;
import com.hrsecurity.dto.RotateResult;
import com.hrsecurity.service.DataKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 密钥管理接口（仅 ADMIN）：对应 docs/key-management.md 里的轮换流程。
 *
 * 四条接口就是一次完整轮换的四个动作：
 *   GET  /api/admin/keys                 看现状（谁 ACTIVE / 谁 RETIRED）
 *   POST /api/admin/keys/rotate          轮换：新密钥上线，旧密钥退役（不停机）
 *   POST /api/admin/keys/reencrypt       存量重加密（幂等，可反复跑到残留 0）
 *   POST /api/admin/keys/{keyId}/disable 确认无残留后停用旧密钥
 *
 * 权限：与加密迁移接口同一口径（仅 ADMIN）。密钥是最"上游"的资产，
 * 能改密钥的人等于能决定全库数据读不读得出来，这类接口绝不开放给普通账号。
 * 四个动作全部有审计埋点（在 Service 方法上），密钥变更史可从审计日志里倒查。
 */
@RestController
@RequestMapping("/api/admin/keys")
@RequiredArgsConstructor
public class KeyAdminController {

    private final DataKeyService dataKeyService;

    /** 密钥列表：GET /api/admin/keys（不含任何密钥材料，只有 keyId/状态/指纹/时间） */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<DataKeyVO>> list() {
        return Result.success(dataKeyService.list());
    }

    /** 轮换：POST /api/admin/keys/rotate */
    @PostMapping("/rotate")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<RotateResult> rotate() {
        return Result.success("密钥轮换完成：新写入已使用新密钥，老数据仍可正常读取",
                dataKeyService.rotate());
    }

    /**
     * 存量重加密：POST /api/admin/keys/reencrypt?batchSize=500&maxBatches=100
     * 幂等：没有任何老密文时返回 reencrypted=0、allDone=true。
     */
    @PostMapping("/reencrypt")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<ReencryptResult> reencrypt(@RequestParam(defaultValue = "500") int batchSize,
                                             @RequestParam(defaultValue = "100") int maxBatches) {
        ReencryptResult result = dataKeyService.reencrypt(batchSize, maxBatches);
        String message = result.isAllDone()
                ? "重加密完成：全库已无老密钥密文"
                : "重加密未完成：仍有 " + result.getRemaining() + " 行残留，可再次调用续跑";
        return Result.success(message, result);
    }

    /** 停用密钥（有残留则拒绝）：POST /api/admin/keys/{keyId}/disable */
    @PostMapping("/{keyId}/disable")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<DataKeyVO> disable(@PathVariable String keyId) {
        return Result.success("密钥已停用：" + keyId, dataKeyService.disable(keyId));
    }
}
