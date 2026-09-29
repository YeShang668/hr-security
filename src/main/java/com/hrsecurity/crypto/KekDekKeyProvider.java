package com.hrsecurity.crypto;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hrsecurity.entity.SysDataKey;
import com.hrsecurity.mapper.SysDataKeyMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * KEK/DEK 两级密钥的 {@link KeyProvider} 实现（第 7 周）：**信封加密（Envelope Encryption）**。
 *
 * 结构：
 *   KEK（环境变量 AES_MASTER_KEY / 生产用 KMS，不落库）
 *     └─ 用 KEK 派生的包装子密钥，AES-256-GCM 加密 DEK
 *          └─ DEK 密文存 sys_data_key；DEK 明文只在启动时解密进内存
 *               └─ DEK 加密业务字段密文 v1:{keyId}:{iv}:{ct}
 *
 * 业务代码零改动（这是本周最想证明的设计结论）：
 * 第 6 周把"密钥从哪来"抽成 {@link KeyProvider} 接口后，本周只是**换了一个实现**——
 * AesGcmCipher、AesTypeHandler、所有 Service、所有 Controller 一行都没动。
 * 对照组：如果当初把 EnvKeyProvider 直接写进 AesGcmCipher 里，这次就得改加解密器本身。
 *
 * 启动自检（fail fast，两条）：
 * 1. sys_data_key 为空 → 引导生成第一个 DEK（新环境/首次部署的正常路径，日志会 WARN 提醒）；
 * 2. ACTIVE 状态的 DEK **必须恰好一把** → 否则拒绝启动。
 *    0 把：没有密钥能加密新数据；2 把：到底用哪把加密？这种"看起来能用"的状态最危险，
 *    宁可启动失败也不要带着不确定的密钥体系对外服务（与 JWT_SECRET/KEK 缺失即失败同一原则）。
 *
 * 内存里的密钥材料：
 * - ACTIVE / RETIRED 的 DEK 明文常驻内存（轮换期间老密文随时可能被读到，必须能解）；
 * - DISABLED 的 DEK **不加载**（连内存里都不留），解密请求直接报错——
 *   这样"以为轮换完了、其实还有老密文"的问题会在第一次读取时立刻暴露，而不是静默可解；
 * - 日志只打 keyId 与指纹（SHA-256 前 8 位），密钥材料绝不进日志/异常消息。
 */
@Slf4j
@Component
public class KekDekKeyProvider implements KeyProvider {

    /** DEK 必须是 32 字节（AES-256） */
    private static final int DEK_BYTES = 32;

    private static final int PARTS = 4;

    /**
     * 域分隔标签：用 KEK 派生"包装 DEK 用的子密钥"。
     * 不直接拿 KEK 当加密密钥用（域分隔是密码学惯例：一个根密钥派生多用途子密钥时，
     * 必须用不同标签，避免同一份密钥材料跨用途复用）。
     */
    private static final String DEK_WRAP_DOMAIN = "hr-security:kek-wrap-dek:v1";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final KekProvider kekProvider;
    private final SysDataKeyMapper dataKeyMapper;

    /** 首次引导生成的 DEK 版本号（默认 k1；轮换产生的是 k2、k3…） */
    @Value("${crypto.key-id:k1}")
    private String bootstrapKeyId;

    /** keyId → DEK 明文（仅 ACTIVE/RETIRED） */
    private final Map<String, byte[]> deks = new HashMap<>();

    /** keyId → 状态（含 DISABLED，用于给出精确的报错信息） */
    private final Map<String, DataKeyStatus> statuses = new HashMap<>();

    /** keyId → DEK 指纹（只用于日志/界面展示"哪把密钥"，不可反推密钥） */
    private final Map<String, String> fingerprints = new HashMap<>();

    /** 当前用于加密的 keyId（volatile：轮换后其它线程要立刻看到新值） */
    private volatile String activeKeyId;

    public KekDekKeyProvider(KekProvider kekProvider, SysDataKeyMapper dataKeyMapper) {
        this.kekProvider = kekProvider;
        this.dataKeyMapper = dataKeyMapper;
    }

    @PostConstruct
    public void init() {
        bootstrapIfEmpty();
        load();
    }

    @Override
    public String currentKeyId() {
        String current = activeKeyId;
        if (current == null) {
            throw new IllegalStateException("DEK 尚未加载完成，无法加密（启动自检未通过？）");
        }
        return current;
    }

    @Override
    public byte[] key(String requestedKeyId) {
        DataKeyStatus status = statuses.get(requestedKeyId);
        if (status == null) {
            // 未知 keyId 必须报错而不是"拿当前密钥硬解"：硬解只会得到认证标签失败或乱码
            throw new IllegalStateException("未知密钥版本 " + requestedKeyId
                    + "：sys_data_key 中不存在该 keyId（密文来自其他密钥体系？还是手动改过库？）");
        }
        if (status == DataKeyStatus.DISABLED) {
            // 这是"轮换是否彻底"的验收点：还有 DISABLED 密钥的密文，说明重加密没做完
            throw new IllegalStateException("密钥 " + requestedKeyId
                    + " 已停用（DISABLED），拒绝解密：该密文应已被重加密为当前密钥 "
                    + currentKeyId() + "，请检查重加密任务是否执行完整");
        }
        byte[] dek = deks.get(requestedKeyId);
        if (dek == null) {
            throw new IllegalStateException("密钥 " + requestedKeyId + " 的密钥材料不在内存中（状态=" + status + "）");
        }
        return dek;
    }

    /** 生成一把全新随机 DEK 并用 KEK 信封加密，返回可落库的密文串（明文 DEK 不外传） */
    public String wrapNewDek() {
        byte[] dek = new byte[DEK_BYTES];
        RANDOM.nextBytes(dek);
        return wrap(dek);
    }

    /** 非活跃（RETIRED / DISABLED）的 keyId：重加密任务的"待处理"目标集合 */
    public List<String> staleKeyIds() {
        List<String> ids = new ArrayList<>();
        statuses.forEach((keyId, status) -> {
            if (status != DataKeyStatus.ACTIVE) {
                ids.add(keyId);
            }
        });
        ids.sort(String::compareTo);
        return ids;
    }

    /** DEK 指纹（SHA-256 前 8 位）；DISABLED 的密钥材料不加载，因此返回 null */
    public String dekFingerprint(String keyId) {
        return fingerprints.get(keyId);
    }

    /**
     * 重新读取 sys_data_key 并解密 DEK（轮换/停用后调用）。
     * synchronized：重新加载期间不能有线程看到"半新半旧"的密钥集合。
     */
    public synchronized void reload() {
        load();
    }

    // ---------- 内部 ----------

    /** 空库引导：新环境第一次启动时生成 k1（生产首次部署也走这条路，故用 WARN 级别提醒） */
    private void bootstrapIfEmpty() {
        Long count = dataKeyMapper.selectCount(null);
        if (count != null && count > 0) {
            return;
        }
        SysDataKey key = new SysDataKey();
        key.setKeyId(bootstrapKeyId);
        key.setEncryptedDek(wrapNewDek());
        key.setStatus(DataKeyStatus.ACTIVE.name());
        key.setCreatedAt(LocalDateTime.now());
        dataKeyMapper.insert(key);
        log.warn("sys_data_key 为空：已引导生成首个 DEK（keyId={}），并用 KEK（kekId={}）信封加密落库。"
                + "生产环境请确认这是预期动作。", bootstrapKeyId, kekProvider.kekId());
    }

    private void load() {
        List<SysDataKey> rows = dataKeyMapper.selectList(
                new LambdaQueryWrapper<SysDataKey>().orderByAsc(SysDataKey::getId));
        Map<String, byte[]> newDeks = new HashMap<>();
        Map<String, DataKeyStatus> newStatuses = new HashMap<>();
        Map<String, String> newFingerprints = new HashMap<>();
        List<String> actives = new ArrayList<>();
        List<String> loaded = new ArrayList<>();

        for (SysDataKey row : rows) {
            DataKeyStatus status = parseStatus(row);
            newStatuses.put(row.getKeyId(), status);
            if (status == DataKeyStatus.ACTIVE) {
                actives.add(row.getKeyId());
            }
            if (status == DataKeyStatus.DISABLED) {
                // 停用的 DEK 不加载密钥材料：连内存里都不留，杜绝"悄悄还能解"
                continue;
            }
            byte[] dek = unwrap(row);
            newDeks.put(row.getKeyId(), dek);
            newFingerprints.put(row.getKeyId(), EnvKeyProvider.fingerprintOf(dek));
            loaded.add(row.getKeyId() + "/" + status + "/" + newFingerprints.get(row.getKeyId()));
        }

        if (actives.size() != 1) {
            throw new IllegalStateException("sys_data_key 自检失败：ACTIVE 的 DEK 必须恰好一把，当前 "
                    + actives.size() + " 把 " + actives
                    + "。0 把无法加密新数据，多把则无法确定用哪把加密；请人工修正后重启。");
        }

        deks.clear();
        deks.putAll(newDeks);
        statuses.clear();
        statuses.putAll(newStatuses);
        fingerprints.clear();
        fingerprints.putAll(newFingerprints);
        this.activeKeyId = actives.get(0);
        log.info("DEK 已加载：{}（共 {} 把，其中 DISABLED 不加载密钥材料）；currentKeyId={}",
                loaded.isEmpty() ? "无" : loaded, rows.size(), activeKeyId);
    }

    private DataKeyStatus parseStatus(SysDataKey row) {
        try {
            return DataKeyStatus.valueOf(row.getStatus());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("sys_data_key 中 keyId=" + row.getKeyId()
                    + " 的状态非法：" + row.getStatus() + "（应为 ACTIVE/RETIRED/DISABLED）", e);
        }
    }

    /** 用 KEK 派生的包装子密钥做 GCM 加密，格式 v1:{kekId}:{iv}:{ct}（与字段密文同一套信封格式） */
    private String wrap(byte[] dek) {
        byte[] iv = AesGcmCodec.newIv();
        byte[] ct = AesGcmCodec.encrypt(wrapKey(), iv, dek);
        return AesGcmCodec.VERSION + AesGcmCodec.SEP + kekProvider.kekId() + AesGcmCodec.SEP
                + b64(iv) + AesGcmCodec.SEP + b64(ct);
    }

    private byte[] unwrap(SysDataKey row) {
        String stored = row.getEncryptedDek();
        String[] parts = stored == null ? new String[0] : stored.split(AesGcmCodec.SEP);
        if (parts.length != PARTS || !AesGcmCodec.VERSION.equals(parts[0])) {
            throw new IllegalStateException("sys_data_key.key_id=" + row.getKeyId()
                    + " 的 encrypted_dek 格式非法（应为 v1:kekId:iv:ct）：" + brief(stored));
        }
        if (!kekProvider.kekId().equals(parts[1])) {
            // 换过 KEK 才会走到这里：DEK 是用另一把 KEK 包的，当前 KEK 解不开
            throw new IllegalStateException("DEK " + row.getKeyId() + " 是用 KEK " + parts[1]
                    + " 加密的，当前 KEK 是 " + kekProvider.kekId()
                    + "：说明 AES_MASTER_KEY 被换过。必须先恢复原 KEK，或用原 KEK 解密后重新封装（见 docs/key-management.md）");
        }
        return AesGcmCodec.decrypt(wrapKey(), unb64(parts[2]), unb64(parts[3]));
    }

    private byte[] wrapKey() {
        return EnvKeyProvider.domainSeparatedKey(kekProvider.kek(), DEK_WRAP_DOMAIN);
    }

    private String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    private byte[] unb64(String data) {
        try {
            return Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("encrypted_dek 的 Base64 段非法", e);
        }
    }

    /** 只在异常里给前 16 个字符，避免把整段 DEK 密文写进日志 */
    private String brief(String stored) {
        if (stored == null) {
            return "null";
        }
        return stored.length() <= 16 ? stored : stored.substring(0, 16) + "...";
    }
}
