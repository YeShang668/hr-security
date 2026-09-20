package com.hrsecurity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 历史数据加密迁移结果（幂等刷数的执行报告）。
 * 每次调用都返回本次实际做了什么，便于运维核对与重复执行时确认"没有重复加密"。
 */
@Data
@AllArgsConstructor
public class BackfillResult {

    /** 扫描到的旧系统明文行数 */
    private int scanned;

    /** 本次加密写入的条数 */
    private int migrated;

    /** 已有密文、本次跳过（幂等生效）的条数 */
    private int skipped;

    /** 工号在新员工表中找不到对应记录的条数（需人工核对） */
    private int unmatched;
}
