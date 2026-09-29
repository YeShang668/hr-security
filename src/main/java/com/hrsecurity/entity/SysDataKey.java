package com.hrsecurity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据加密密钥（DEK）实体，对应 sys_data_key。
 *
 * 本表存的是 **DEK 的密文**（encrypted_dek：被 KEK 信封加密后的结果），
 * 绝不存明文密钥材料——"密钥表泄露 = 数据全裸"是最不该犯的错误。
 *
 * 为什么 DEK 要落库而 KEK 不落库：
 * - DEK 是"数据密钥"，业务数据成千上万行都靠它，落库才能被应用在运行时取用；
 * - KEK 是"保护密钥的密钥"，只在一个动作里用到（解开 DEK），
 *   放进环境变量/KMS 后，即使数据库整库被拖走，没有 KEK 也拿不到 DEK。
 */
@Data
@TableName("sys_data_key")
public class SysDataKey {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** DEK 版本号（k1/k2/…），会写进密文 v1:{keyId}:{iv}:{ct} */
    private String keyId;

    /** DEK 密文：格式 v1:{kekId}:{ivBase64}:{cipherBase64}，用 KEK 派生出的包装密钥加密 */
    private String encryptedDek;

    /** ACTIVE / RETIRED / DISABLED */
    private String status;

    /** 该 DEK 上线时间 */
    private LocalDateTime createdAt;

    /** 退役时间（可回答"哪个 keyId 什么时候上线、什么时候退役"） */
    private LocalDateTime retiredAt;
}
