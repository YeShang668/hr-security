package com.hrsecurity.crypto;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
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
 *   keyId    → 密钥轮换时老密文仍可解（第 7 周起 keyId 指向 sys_data_key 里的 DEK）；
 *   IV 与密文一起存（IV 不需要保密，但必须唯一）。
 *
 * 第 7 周变化：算法原语下沉到 {@link AesGcmCodec}（与 DEK 信封加密共用），
 * 本类只负责"密钥挑选（调 KeyProvider）+ 信封格式拼装"。
 * 业务代码（Service/TypeHandler）一行没改——这正是第 6 周先抽 KeyProvider 接口的目的。
 */
@Component
@RequiredArgsConstructor
public class AesGcmCipher {

    private static final int PARTS = 4;

    private final KeyProvider keyProvider;

    /**
     * 加密：明文 → v1:{keyId}:{iv}:{ct}，其中 keyId = 当前活跃 DEK。
     * null / 空串按"未填写"处理直接返回，不产生"空值的密文"（避免列表里出现无意义的密文串）。
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        String keyId = keyProvider.currentKeyId();
        byte[] iv = AesGcmCodec.newIv();
        byte[] cipherText = AesGcmCodec.encrypt(keyProvider.key(keyId), iv, bytes(plain));
        return AesGcmCodec.VERSION + AesGcmCodec.SEP + keyId + AesGcmCodec.SEP
                + b64(iv) + AesGcmCodec.SEP + b64(cipherText);
    }

    /**
     * 解密：v1:{keyId}:{iv}:{ct} → 明文。
     * 密钥按密文自带的 keyId 取（轮换后老密文依然解得开），
     * keyId 未知 / 已停用 / 密文被篡改都会抛 IllegalStateException。
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
        String[] parts = stored.split(AesGcmCodec.SEP);
        if (parts.length != PARTS) {
            throw new IllegalStateException("密文格式非法（应为 v1:keyId:iv:ct）：" + brief(stored));
        }
        byte[] plain = AesGcmCodec.decrypt(keyProvider.key(parts[1]), unb64(parts[2]), unb64(parts[3]));
        return new String(plain, StandardCharsets.UTF_8);
    }

    /** 该值是否为本格式的密文（迁移/重加密的判断依据） */
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(AesGcmCodec.VERSION + AesGcmCodec.SEP);
    }

    /** 密文里的 keyId（重加密任务判断"这行是不是老密钥加密的"用）；非密文返回 null */
    public String keyIdOf(String stored) {
        if (!isEncrypted(stored)) {
            return null;
        }
        String[] parts = stored.split(AesGcmCodec.SEP);
        return parts.length == PARTS ? parts[1] : null;
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
