package com.hrsecurity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 存量数据重加密（轮换收尾）执行报告。
 *
 * 每个字段都有明确的验收含义（对应测试用例）：
 * - activeKeyId：本次重加密的目标密钥（当前活跃 DEK）；
 * - batches：实际处理了几批（每批一个独立事务，中断后可续跑）；
 * - reencrypted：被改写的行数（每次运行都从 0 开始计，重复执行为 0 = 幂等）；
 * - remaining：**执行后**全库仍引用非活跃密钥的行数，为 0 才说明轮换彻底；
 * - allDone：remaining == 0 的便捷标志（前端显隐"停用旧密钥"按钮用）。
 *
 * 注意 remaining 是"行数"而不是"列数"：一行里可能有多个敏感列引用老密钥，
 * 运维关心的是"还有几行可能读不出来"，所以按行统计。
 */
@Data
@AllArgsConstructor
public class ReencryptResult {

    private String activeKeyId;

    private int batches;

    private int reencrypted;

    private long remaining;

    private boolean allDone;
}
