package com.hrsecurity.service;

import com.hrsecurity.dto.DataKeyVO;
import com.hrsecurity.dto.ReencryptResult;
import com.hrsecurity.dto.RotateResult;

import java.util.List;

/**
 * 数据密钥（DEK）生命周期管理：查看、轮换、存量重加密、停用。
 *
 * 一次完整轮换的运维流程（对应四个方法）：
 *   1. list()   看清现状：哪把 ACTIVE、哪把 RETIRED；
 *   2. rotate() 新 DEK 上线（旧 DEK 变 RETIRED）：**新写入立刻用新密钥，老数据无需停机**；
 *   3. reencrypt() 分批把老密文改成新密钥（幂等、可中断、可重复跑），直到 remaining=0；
 *   4. disable()  确认没有残留后停用老密钥（还有残留就拒绝，防止"停用即读不出来"）。
 *
 * 为什么不能"一把梭"：直接换一把全新的密钥、把老的全停用，等于让全库数据立刻不可读。
 * 真正的轮换是"双密钥并存 → 慢慢转换 → 最后退役"的过程。
 */
public interface DataKeyService {

    /** 密钥现状（含 DEK 指纹，但不含任何密钥材料） */
    List<DataKeyVO> list();

    /** 轮换：生成新 DEK 并置为 ACTIVE，旧 DEK 置 RETIRED（老密文仍可解密） */
    RotateResult rotate();

    /**
     * 存量数据重加密：把非活跃 keyId 的密文按批读出 → 解密 → 用当前 DEK 重新加密写回。
     * 幂等（重复执行第二次 0 条）、可中断、有批次上限（避免一次请求跑太久）。
     *
     * @param batchSize  每批处理行数（默认 500）
     * @param maxBatches 单次调用最多跑几批（默认 100，防止超长请求；没跑完可再调一次续跑）
     */
    ReencryptResult reencrypt(int batchSize, int maxBatches);

    /** 停用密钥（仅当已无任何密文引用该 keyId 时才允许） */
    DataKeyVO disable(String keyId);
}
