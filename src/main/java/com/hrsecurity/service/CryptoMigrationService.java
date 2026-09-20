package com.hrsecurity.service;

import com.hrsecurity.dto.BackfillResult;

/**
 * 历史数据加密迁移：把旧系统明文表（legacy_employee_plain）里的敏感字段
 * 加密刷入 sys_employee 的密文列。
 *
 * 为什么刷数要独立成一个服务（而不是写一段脚本跑一次）：
 * - 幂等可重复执行：迁移可能中断、可能补数据，重复跑不能把已有密文覆盖掉；
 * - 有执行报告：扫描/迁移/跳过/未匹配四条计数，跑完能核对；
 * - 与业务解耦：迁移逻辑不混在员工 CRUD 里，上线后可保留为运维手段。
 */
public interface CryptoMigrationService {

    /** 执行迁移，返回本次执行报告（幂等：已迁移的记录计入 skipped） */
    BackfillResult backfill();
}
