package com.hrsecurity.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * AES-256-GCM 的**纯算法层**：只做"给定密钥 + 给定 IV 的加解密"，不管密钥从哪来、不管密文怎么拼装。
 *
 * 为什么要把这一层单独抽出来（第 7 周新增）：
 * {@link AesGcmCipher} 依赖 {@link KeyProvider}（业务字段加密用 DEK），
 * 而 KEK/DEK 信封加密里"用 KEK 加密 DEK"也必须用同一套 GCM 参数；
 * 如果那时还去调 AesGcmCipher，就会出现
 * 「AesGcmCipher → KeyProvider(KekDekKeyProvider) → AesGcmCipher」的循环依赖。
 * 把算法原语下沉成无依赖的静态工具类，两边共用一份实现，参数也永远一致
 * （IV 长度、标签长度这类参数如果两处各写一份，早晚会写歪）。
 */
final class AesGcmCodec {

    /** GCM 模式：AEAD 认证加密，改一位密文就解不开 */
    static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** GCM 推荐 IV 长度 96 位（12 字节） */
    static final int IV_BYTES = 12;

    /** 认证标签 128 位（GCM 支持的最长值） */
    static final int TAG_BITS = 128;

    /** 密文格式版本号：换算法（如国密 SM4）时递增，可与老格式并存 */
    static final String VERSION = "v1";

    /** 分隔符：Base64 字母表不含 ':'，可安全 split */
    static final String SEP = ":";

    private static final SecureRandom RANDOM = new SecureRandom();

    private AesGcmCodec() {
    }

    /** 每次加密都必须用新随机 IV：GCM 下同一密钥复用 IV 会泄露明文关系并可恢复认证密钥 */
    static byte[] newIv() {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        return iv;
    }

    static byte[] encrypt(byte[] key, byte[] iv, byte[] plain) {
        return doFinal(Cipher.ENCRYPT_MODE, key, iv, plain);
    }

    static byte[] decrypt(byte[] key, byte[] iv, byte[] cipherText) {
        return doFinal(Cipher.DECRYPT_MODE, key, iv, cipherText);
    }

    private static byte[] doFinal(int mode, byte[] key, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(input);
        } catch (AEADBadTagException e) {
            // 完整性保护生效：密文被改 / IV 不匹配 / 密钥不对，都在这里被拦下（不返回乱码）
            throw new IllegalStateException("密文完整性校验失败（数据被篡改或密钥不匹配），拒绝解密", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("加解密失败：" + e.getMessage(), e);
        }
    }
}
