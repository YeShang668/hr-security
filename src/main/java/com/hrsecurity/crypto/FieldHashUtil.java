package com.hrsecurity.crypto;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 可检索字段的"盲索引"哈希：HMAC-SHA256(身份证号) → 64 位十六进制，存 sys_employee.id_card_hash。
 *
 * 要解决什么：字段加密后 SQL 里没有明文可比，"按身份证精确查人"和"身份证唯一校验"都会失效。
 * 做法：加密照旧存密文（能还原明文），另外存一列哈希值只用于等值匹配。
 *
 * 为什么是 HMAC-SHA256 而不是裸 SHA-256（面试必答）：
 * 身份证号空间有限（地区码+生日+顺序码），裸 SHA-256 可被彩虹表/暴力枚举反推；
 * 加一个密钥（盐）后，攻击者即使拿到数据库也造不出表——除非同时拿到密钥。
 * 为什么不用可逆加密做检索：那等于把"可检索"降级成"可解密"，失去了哈希单向的价值。
 *
 * 哈希密钥来源：环境变量 ID_HASH_SALT 优先；未配置时由 **KEK** 做**域分隔派生**
 * （SHA-256("hr-security:id-card-hash:v1" + KEK)），这样不必多管一个密钥，
 * 又不会与加密密钥直接共用。注意：盐/KEK 变了，历史哈希全部失效，需重建索引列。
 *
 * 第 7 周注意：哈希盐刻意继续从 **KEK** 派生（而不是 DEK）——
 * DEK 一旦轮换，如果盐跟着变，id_card_hash 全库失效（检索与唯一校验立刻不可用）。
 * 哈希只与 KEK 绑定，轮换 DEK 时哈希列完全不受影响，这也是"两级密钥"的一个实际收益。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FieldHashUtil {

    /** 域分隔标签：同一主密钥派生不同用途子密钥时用不同标签 */
    private static final String DOMAIN = "hr-security:id-card-hash:v1";

    private static final String ALGORITHM = "HmacSHA256";

    @Value("${crypto.hash-salt:}")
    private String hashSaltConfig;

    private final KekProvider kekProvider;

    private byte[] hashKey;

    @PostConstruct
    public void init() {
        String salt = System.getenv("ID_HASH_SALT");
        if (salt == null || salt.isBlank()) {
            salt = hashSaltConfig;
        }
        if (salt != null && !salt.isBlank()) {
            this.hashKey = salt.trim().getBytes(StandardCharsets.UTF_8);
            log.info("检索哈希密钥来源：显式盐（ID_HASH_SALT / crypto.hash-salt）");
            return;
        }
        // 未显式配置：从 KEK 做域分隔派生（EnvKeyProvider 已保证 KEK 存在且为 32 字节）
        this.hashKey = EnvKeyProvider.domainSeparatedKey(kekProvider.kek(), DOMAIN);
        log.info("检索哈希密钥来源：由 KEK 域分隔派生（未配置 ID_HASH_SALT），与 DEK 轮换解耦");
    }

    /** 计算可检索哈希（小写十六进制，64 位）。null/空串返回 null，不产生"空值的哈希" */
    public String hmacSha256Hex(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(hashKey, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("检索哈希计算失败：" + e.getMessage(), e);
        }
    }

    /** 调试用：把哈希密钥指纹打出来，便于确认"没换过盐" */
    public String keyFingerprint() {
        return Base64.getEncoder().encodeToString(hashKey).substring(0, 8);
    }
}
