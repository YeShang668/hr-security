package com.hrsecurity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 密钥轮换结果。
 * previousKeyId 用于回答"这次轮换是从谁换到谁"——密钥变更是高频追责点，
 * 返回值里带上新老 keyId，前端与审计日志都能直接展示。
 */
@Data
@AllArgsConstructor
public class RotateResult {

    /** 轮换前的活跃 DEK（已置为 RETIRED，仍可解密老密文） */
    private String previousKeyId;

    /** 轮换后新的活跃 DEK（之后的写入都用它） */
    private String newKeyId;
}
