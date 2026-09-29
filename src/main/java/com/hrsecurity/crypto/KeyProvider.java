package com.hrsecurity.crypto;

/**
 * 数据密钥提供者：把"密钥从哪来"与"怎么加解密"解耦。
 *
 * 演进记录（这就是"先抽接口"的价值）：
 * - 第 6 周实现 = {@link EnvKeyProvider}：密钥直接来自环境变量 AES_MASTER_KEY；
 * - 第 7 周实现 = {@link KekDekKeyProvider}：KEK 加密 DEK 存库、支持密钥轮换。
 *   换实现时 AesGcmCipher / AesTypeHandler / 所有 Service 与 Controller **一行都没改**。
 *
 * keyId 是密钥版本号，会写进密文（v1:{keyId}:{iv}:{ct}）：
 * 轮换后新数据用新 keyId 加密，老密文按老 keyId 取老密钥仍能解开，实现平滑轮换。
 */
public interface KeyProvider {

    /** 当前用于加密的密钥版本（新数据用它加密；实现里应指向"当前活跃 DEK"） */
    String currentKeyId();

    /**
     * 取指定 keyId 的 32 字节 AES-256 密钥（解密老密文时用）。
     * 未知 keyId 必须抛异常而不是返回 null/空密钥，避免用错密钥静默解出乱码；
     * 已停用（DISABLED）的 keyId 同样必须抛异常——那是"重加密没做完"的信号，要吵出来。
     */
    byte[] key(String keyId);
}
