package com.hrsecurity.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 密钥列表出参（前端"密钥管理"页）。
 *
 * 刻意**不含**任何密钥材料：
 * - 不给 encrypted_dek：虽然它本身是密文，但把它吐给前端没有任何用途，只是扩大暴露面；
 * - dekFingerprint 是"DEK 明文的 SHA-256 前 8 位"，用于人工确认"哪把密钥、换没换"，
 *   8 位十六进制（32 bit）不足以反推密钥；
 * - DISABLED 的密钥材料不在内存里，其指纹为 null（前端显示"—"）。
 */
@Data
@Builder
public class DataKeyVO {

    /** DEK 版本号：k1/k2/…，对应密文里的 keyId */
    private String keyId;

    /** ACTIVE / RETIRED / DISABLED */
    private String status;

    /** DEK 指纹（SHA-256 前 8 位）；DISABLED 时为 null */
    private String dekFingerprint;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime retiredAt;
}
