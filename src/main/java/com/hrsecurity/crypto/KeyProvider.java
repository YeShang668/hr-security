package com.hrsecurity.crypto;

/**
 * 数据密钥提供者：把"密钥从哪来"与"怎么加解密"解耦。
 *
 * 本周（第 6 周）实现是 {@link EnvKeyProvider}：密钥来自环境变量 AES_MASTER_KEY；
 * 下周（第 7 周）换成 KEK/DEK 两级密钥实现（KEK 加密 DEK 存库、支持密钥轮换）时，
 * AesGcmCipher / TypeHandler / 业务代码一行都不用改——这就是先抽接口的目的。
 *
 * keyId 是密钥版本号，会写进密文（v1:{keyId}:{iv}:{ct}）：
 * 轮换后新数据用新 keyId 加密，老密文按老 keyId 取老密钥仍能解开，实现平滑轮换。
 */
public interface KeyProvider {

    /** 当前用于加密的密钥版本（新数据用它加密） */
    String currentKeyId();

    /**
     * 取指定 keyId 的 32 字节 AES-256 密钥（解密老密文时用）。
     * 未知 keyId 必须抛异常而不是返回 null/空密钥，避免用错密钥静默解出乱码。
     */
    byte[] key(String keyId);
}
