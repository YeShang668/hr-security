package com.hrsecurity.crypto;

/**
 * DEK（数据加密密钥）的生命周期状态。
 *
 * 三种状态构成一次完整的密钥轮换：
 *   ACTIVE（新数据用它加密）→ RETIRED（已退役，仍能解密老密文）→ DISABLED（彻底停用，拒绝解密）
 *
 * 状态机规则（由 DataKeyServiceImpl 保证）：
 * - 任何时刻 **ACTIVE 恰好一把**（启动时自检，不满足直接 fail fast）；
 * - ACTIVE → RETIRED 由"轮换"触发（同一次操作里把新 DEK 置为 ACTIVE）；
 * - RETIRED → DISABLED 只允许在"全库已无该 keyId 密文"时执行，
 *   否则停用会让那批数据变成不可解密（见 KeyAdminController 的残留校验）。
 *
 * 为什么 RETIRED 不能直接删：删掉就再也解不开老密文了；
 * 轮换期间必须容忍"新老密文并存"，直到重加密任务跑完。
 */
public enum DataKeyStatus {

    /** 当前活跃：新写入的密文都用它加密 */
    ACTIVE,

    /** 已退役：不再用于加密，但仍持有密钥材料，老密文继续可解 */
    RETIRED,

    /** 已停用：密钥材料不再加载进内存，解密请求直接报错（防止"以为轮换完了其实还有老密文"） */
    DISABLED
}
