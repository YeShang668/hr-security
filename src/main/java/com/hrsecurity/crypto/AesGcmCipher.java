package com.hrsecurity.crypto;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 字段级 AES-256-GCM 加解密，使用 JDK 自带 javax.crypto（毕设要求：不自己实现算法，也不乱引三方库）。
 *
 * 参数与理由（面试/答辩必答）：
 * - AES-256：密钥 32 字节，对称加密，性能足以支撑"每字段加解密"；
 * - GCM 模式：AEAD（认证加密），加密同时产出 128 位认证标签，"改一位密文就解不开"，
 *   天然抗篡改与选择密文攻击；比 CBC 少了"必须再做 HMAC"的坑；
 * - IV 12 字节随机、**每次加密都重新生成**：GCM 里同一个密钥下 IV 复用会导致
 *   （1）异或泄露明文关系（2）认证密钥被恢复伪造密文——这是 GCM 最著名的致命坑，绝不踩；
 * - 认证标签 128 位（GCM 支持的最长值，最安全）。
 *
 * 密文格式（自描述，与密钥管理演进兼容）：
 *   v1:{keyId}:{ivBase64}:{cipherBase64}
 *   版本号 v1 → 将来换算法（如国密 SM4）可并存；
 *   keyId    → 密钥轮换时老密文仍可解；
 *   IV 与密文一起存（IV 不需要保密，但必须唯一）。
 */
@Component
@RequiredArgsConstructor
public class AesGcmCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** GCM 推荐 IV 长度 96 位（12 字节） */
    private static final int IV_BYTES = 12;

    /** 认证标签 128 位 */
    private static final int TAG_BITS = 128;

    /** 密文格式版本号 */
    private static final String VERSION = "v1";

    /** 分隔符：Base64 字母表不含 ':'，可安全 split */
    private static final String SEP = ":";

    private static final int PARTS = 4;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final KeyProvider keyProvider;

    /**
     * 加密：明文 → v1:{keyId}:{iv}:{ct}
     * null / 空串按"未填写"处理直接返回，不产生"空值的密文"（避免列表里出现无意义的密文串）。
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        String keyId = keyProvider.currentKeyId();
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        byte[] cipherText = doFinal(Cipher.ENCRYPT_MODE, keyProvider.key(keyId), iv, bytes(plain));
        return VERSION + SEP + keyId + SEP + b64(iv) + SEP + b64(cipherText);
    }

    /**
     * 解密：v1:{keyId}:{iv}:{ct} → 明文。
     * 格式非法、密钥不匹配、密文被篡改（认证标签校验失败）都会抛 IllegalStateException。
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        if (!isEncrypted(stored)) {
            // 历史遗留明文（或人工误写入）：不静默当明文返回，也不假装解密成功，
            // 直接报错让人发现"这行没迁移"，避免把明文当密文一路带着跑
            throw new IllegalStateException("字段不是合法的 v1 密文格式，疑似明文或格式损坏：" + brief(stored));
        }
        String[] parts = stored.split(SEP);
        if (parts.length != PARTS) {
            throw new IllegalStateException("密文格式非法（应为 v1:keyId:iv:ct）：" + brief(stored));
        }
        byte[] key = keyProvider.key(parts[1]);
        byte[] iv = unb64(parts[2]);
        byte[] cipherText = unb64(parts[3]);
        byte[] plain = doFinal(Cipher.DECRYPT_MODE, key, iv, cipherText);
        return new String(plain, StandardCharsets.UTF_8);
    }

    /** 该值是否为本格式的密文（迁移幂等判断用） */
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(VERSION + SEP);
    }

    private byte[] doFinal(int mode, byte[] key, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(input);
        } catch (AEADBadTagException e) {
            // GCM 的"完整性保护生效"：密文被改 / IV 不匹配 / 密钥不对，都在这里被拦下
            throw new IllegalStateException("密文完整性校验失败（数据被篡改或密钥不匹配），拒绝解密", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("加解密失败：" + e.getMessage(), e);
        }
    }

    private byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    private byte[] unb64(String data) {
        try {
            return Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("密文 Base64 段非法", e);
        }
    }

    /** 日志/异常里只给密文的前 16 个字符，避免把整段密文打进日志 */
    private String brief(String stored) {
        return stored.length() <= 16 ? stored : stored.substring(0, 16) + "...";
    }
}
