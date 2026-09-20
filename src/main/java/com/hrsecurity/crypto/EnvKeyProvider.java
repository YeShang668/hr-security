package com.hrsecurity.crypto;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 密钥来源：环境变量 AES_MASTER_KEY 优先，其次本地未提交的 application-local.yml（crypto.master-key）。
 * 两者都缺 → 启动直接失败（fail fast），绝不用"默认密钥"静默跑起来。
 *
 * 为什么必须 fail fast：密钥一旦有默认值，就一定会有人带着默认密钥上线，
 * 这等价于数据库里的密文全部可解——比不加密更危险（有种虚假的安全感）。
 * 这个做法与第 4 周 JWT_SECRET 的处理一致。
 *
 * 密钥格式：Base64（推荐，32 字节 → 44 字符，如 openssl rand -base64 32）
 *          或 64 位十六进制字符串。
 */
@Slf4j
@Component
public class EnvKeyProvider implements KeyProvider {

    /** AES-256 要求密钥长度 32 字节 */
    private static final int KEY_BYTES = 32;

    /** 密文里写入的密钥版本号（下周轮换时会有 k2、k3…） */
    @Value("${crypto.key-id:k1}")
    private String keyId;

    /** 本地开发用（application-local.yml，已 gitignore）；生产只走环境变量 */
    @Value("${crypto.master-key:}")
    private String masterKeyConfig;

    private byte[] masterKey;

    @PostConstruct
    public void init() {
        String raw = System.getenv("AES_MASTER_KEY");
        if (raw == null || raw.isBlank()) {
            raw = masterKeyConfig;
        }
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("""
                    AES 主密钥未配置：请设置环境变量 AES_MASTER_KEY（32 字节密钥的 Base64，
                    生成方式：openssl rand -base64 32），或在本机开发时写入 application-local.yml 的 crypto.master-key。
                    注意：密钥绝不入库、绝不进 git，否则字段级加密形同虚设。""");
        }
        this.masterKey = decode(raw.trim());
        if (masterKey.length != KEY_BYTES) {
            throw new IllegalStateException("AES 主密钥长度必须是 " + KEY_BYTES
                    + " 字节（AES-256），当前为 " + masterKey.length + " 字节");
        }
        // 只打印指纹（密钥哈希前 8 位），便于确认"换没换密钥"而不泄露密钥本身
        log.info("AES 字段加密已启用：currentKeyId={}，主密钥指纹={}", keyId, fingerprint());
    }

    @Override
    public String currentKeyId() {
        return keyId;
    }

    @Override
    public byte[] key(String requestedKeyId) {
        // 本周只有一把密钥（k1）：请求不存在的 keyId 说明密文来自别的密钥体系，
        // 必须报错而不是拿当前密钥硬解（硬解只会得到 AEADBadTag 校验失败或乱码）
        if (!keyId.equals(requestedKeyId)) {
            throw new IllegalStateException("未知密钥版本 " + requestedKeyId
                    + "：当前仅支持 " + keyId + "（密钥轮换见第 7 周 KEK/DEK 设计）");
        }
        return masterKey;
    }

    /** 支持 Base64（44 字符）与十六进制（64 字符）两种写法 */
    private byte[] decode(String raw) {
        if (raw.matches("(?i)[0-9a-f]{64}")) {
            return HexFormat.of().parseHex(raw);
        }
        try {
            return Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("AES 主密钥格式非法：既不是 64 位十六进制，也不是合法 Base64", e);
        }
    }

    /** 密钥指纹：SHA-256 前 8 位十六进制，只用于日志比对 */
    private String fingerprint() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(masterKey);
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }

    /** 供 FieldHashUtil 派生哈希盐用（域分隔，避免哈希密钥与加密密钥直接共用） */
    byte[] rawKey() {
        return masterKey.clone();
    }

    /** 域分隔串：同一主密钥派生出不同用途的子密钥时必须带不同标签 */
    static byte[] domainSeparatedKey(byte[] masterKey, String domain) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(domain.getBytes(StandardCharsets.UTF_8));
            digest.update(masterKey);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
