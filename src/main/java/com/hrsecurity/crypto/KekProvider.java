package com.hrsecurity.crypto;

/**
 * KEK（密钥加密密钥 Key Encryption Key）来源。
 *
 * 第 7 周引入两级密钥后，职责重新划清：
 * - **KEK**：只用来"包"DEK（信封加密），永远只在环境变量/KMS 里，**不落库、不进 git**；
 * - **DEK**（{@link KeyProvider}）：真正加密业务字段的密钥，以密文形式存在 sys_data_key 表。
 *
 * 为什么必须分两级（面试必答）：
 * 1. 只用一把密钥：换密钥 = 解密全库 + 重加密全库，期间要么停机要么写双份，代价极大；
 * 2. 密钥泄露面：业务库（含密文）泄露时不等于密钥泄露——攻击者拿到 DEK 密文但没有 KEK 也解不开；
 * 3. 轮换成本：换 DEK 不用动 KEK（KEK 往往在 KMS/HSM 里，换它要走密钥管理流程）；
 * 4. 数据密钥可用性：DEK 丢失可用 KEK 恢复（DEK 密文就在库里），不需要备份每把数据密钥。
 *
 * 本接口只有 3 个方法，是为了让"KEK 从哪来"可以替换（今天是环境变量，生产换 KMS/Vault 时
 * 只加一个实现类，密钥体系其余部分一行不改）。
 */
public interface KekProvider {

    /** 32 字节 AES-256 KEK（调用方不应修改返回值内容） */
    byte[] kek();

    /** KEK 版本号（写进 DEK 密文 `v1:{kekId}:{iv}:{ct}`，便于将来轮换 KEK 时识别老密文） */
    String kekId();

    /** KEK 指纹（SHA-256 前 8 位）：只用于日志确认"换没换 KEK"，绝不泄露密钥本身 */
    String fingerprint();
}
