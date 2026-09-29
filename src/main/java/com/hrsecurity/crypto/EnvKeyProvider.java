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
 * KEK 来源：环境变量 AES_MASTER_KEY 优先，其次本地未提交的 application-local.yml（crypto.master-key）。
 * 两者都缺 → 启动直接失败（fail fast），绝不用"默认密钥"静默跑起来。
 *
 * 语义变更（第 7 周）：同一个环境变量名，第 6 周它是"业务字段的加密密钥"，
 * 第 7 周起它是 **KEK**（只用于包 DEK）。沿用它是为了兼容既有部署脚本与密钥管理习惯，
 * 也避免"多一个环境变量就多一个忘配置的机会"；数据密钥（DEK）改为落库、由它保护。
 *
 * 为什么必须 fail fast：KEK 一旦有默认值，就一定会有人带着默认密钥上线，
 * 这等价于所有 DEK 密文可解、最终所有密文可解——比不加密更危险（有种虚假的安全感）。
 * 这个做法与第 4 周 JWT_SECRET 的处理一致。
 *
 * 密钥格式：Base64（推荐，32 字节 → 44 字符，如 openssl rand -base64 32）
 *          或 64 位十六进制字符串。
 */
@Slf4j
@Component
public class EnvKeyProvider implements KekProvider {

    /** AES-256 要求密钥长度 32 字节 */
    private static final int KEY_BYTES = 32;

    /** KEK 版本号（写进 DEK 密文头部；将来轮换 KEK 时用它区分新老封装） */
    @Value("${crypto.kek-id:kek1}")
    private String kekId;

    /** 本地开发用（application-local.yml，已 gitignore）；生产只走环境变量 */
    @Value("${crypto.master-key:}")
    private String masterKeyConfig;

    private byte[] kek;

    @PostConstruct
    public void init() {
        String raw = System.getenv("AES_MASTER_KEY");
        if (raw == null || raw.isBlank()) {
            raw = masterKeyConfig;
        }
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("""
                    KEK（密钥加密密钥）未配置：请设置环境变量 AES_MASTER_KEY（32 字节密钥的 Base64，
                    生成方式：openssl rand -base64 32），或在本机开发时写入 application-local.yml 的 crypto.master-key。
                    注意：KEK 绝不入库、绝不进 git，否则 DEK 与全库字段加密形同虚设。""");
        }
        this.kek = decode(raw.trim());
        if (kek.length != KEY_BYTES) {
            throw new IllegalStateException("KEK 长度必须是 " + KEY_BYTES
                    + " 字节（AES-256），当前为 " + kek.length + " 字节");
        }
        log.info("KEK 已加载：kekId={}，指纹={}（KEK 只用于信封加密 DEK，不直接加密业务字段）",
                kekId, fingerprint());
    }

    @Override
    public byte[] kek() {
        return kek;
    }

    @Override
    public String kekId() {
        return kekId;
    }

    @Override
    public String fingerprint() {
        return fingerprintOf(kek);
    }

    /**
     * 供 FieldHashUtil 派生检索哈希盐用（域分隔，避免哈希密钥与加密密钥直接共用）。
     * 域分隔的用法见 {@link #domainSeparatedKey}；KEK→DEK 包装的域标签定义在 KekDekKeyProvider。
     */
    byte[] rawKey() {
        return kek.clone();
    }

    /** 支持 Base64（44 字符）与十六进制（64 字符）两种写法 */
    private byte[] decode(String raw) {
        if (raw.matches("(?i)[0-9a-f]{64}")) {
            return HexFormat.of().parseHex(raw);
        }
        try {
            return Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("KEK 格式非法：既不是 64 位十六进制，也不是合法 Base64", e);
        }
    }

    static String fingerprintOf(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }

    /** 域分隔派生：SHA-256(标签 + 主密钥) → 32 字节子密钥 */
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
