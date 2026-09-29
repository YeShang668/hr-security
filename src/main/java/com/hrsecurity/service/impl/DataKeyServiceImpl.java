package com.hrsecurity.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hrsecurity.audit.AuditLog;
import com.hrsecurity.audit.AuditTrace;
import com.hrsecurity.common.BusinessException;
import com.hrsecurity.common.ResultCode;
import com.hrsecurity.crypto.AesGcmCipher;
import com.hrsecurity.crypto.DataKeyStatus;
import com.hrsecurity.crypto.KekDekKeyProvider;
import com.hrsecurity.dto.DataKeyVO;
import com.hrsecurity.dto.ReencryptResult;
import com.hrsecurity.dto.RotateResult;
import com.hrsecurity.entity.SysDataKey;
import com.hrsecurity.mapper.SysDataKeyMapper;
import com.hrsecurity.service.DataKeyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * DEK 生命周期管理实现。
 *
 * 两个实现细节值得单独讲清楚（面试/答辩都会追问）：
 *
 * 1. **重加密为什么用 JdbcTemplate 直接操作列，而不是走 MyBatis-Plus 实体更新**：
 *    - MP 的 selectById/updateById 受实体上的 @TableLogic 影响，会自动带 `status = 1` 条件，
 *      于是**已离职（逻辑删除）员工的密文永远轮换不到**——"全库不存在老 keyId 密文"这个
 *      验收点会一直不通过，而这类数据恰恰最容易被忽略（离职档案同样是敏感数据）；
 *    - 走实体会整行回写（含所有业务字段），而重加密本质是"存储层维护"，
 *      只应该碰密文列，不该有改动业务语义的机会；
 *    - 按密文列前缀（`id_card_enc LIKE 'v1:k1:%'`）批量筛选本身就是 SQL 的强项。
 *    代价：这里出现裸 SQL。所以列名集中定义为常量、前缀由内部生成（不拼接用户输入），
 *    并且重加密后立刻用接口回归验证（读得出来才算成功）。
 *
 * 2. **分批 + 每批一个独立事务**：不分批会有两个后果——一个大事务锁住大量行、
 *    undo log 膨胀；中途失败要全部重来。用 TransactionTemplate 编程式事务而不是
 *    给方法加 @Transactional，是为了让"一批一提交"显而易见，
 *    也避开 Spring 里"同类方法自调用不走代理、@Transactional 失效"这个经典坑。
 *    批次之间用 id 游标（id > lastId）推进：保证每批都往前走，不会因为个别行解不开而原地死循环。
 */
@Slf4j
@Service
public class DataKeyServiceImpl implements DataKeyService {

    /** 重加密只碰这四列（敏感密文列），不触碰任何业务字段 */
    private static final List<String> SENSITIVE_COLUMNS =
            List.of("phone_enc", "id_card_enc", "bank_card_enc", "salary_enc");

    private static final int DEFAULT_BATCH_SIZE = 500;
    private static final int MAX_BATCH_SIZE = 1000;
    private static final int DEFAULT_MAX_BATCHES = 100;
    private static final int MAX_BATCHES_LIMIT = 1000;

    private final SysDataKeyMapper dataKeyMapper;
    private final KekDekKeyProvider keyProvider;
    private final AesGcmCipher cipher;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public DataKeyServiceImpl(SysDataKeyMapper dataKeyMapper, KekDekKeyProvider keyProvider,
                             AesGcmCipher cipher, JdbcTemplate jdbcTemplate,
                             PlatformTransactionManager transactionManager) {
        this.dataKeyMapper = dataKeyMapper;
        this.keyProvider = keyProvider;
        this.cipher = cipher;
        this.jdbcTemplate = jdbcTemplate;
        // 编程式事务：用它来做到"一批一提交"，且不依赖 Spring AOP 代理
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public List<DataKeyVO> list() {
        return loadAllKeys().stream().map(this::toVO).collect(Collectors.toList());
    }

    @AuditLog(operation = "轮换数据加密密钥", targetType = "KEY",
            detail = "生成新 DEK 并用 KEK 信封加密落库；旧 DEK 置 RETIRED，老密文仍可解（不停机）")
    @Transactional
    @Override
    public RotateResult rotate() {
        String previousKeyId = keyProvider.currentKeyId();
        SysDataKey previous = findOrThrow(previousKeyId);
        String newKeyId = nextKeyId(loadAllKeys());

        // 1. 旧 DEK 退役：只改状态，**不删除**——老密文还要靠它的密钥材料解密
        previous.setStatus(DataKeyStatus.RETIRED.name());
        previous.setRetiredAt(LocalDateTime.now());
        dataKeyMapper.updateById(previous);

        // 2. 新 DEK 上线：随机生成，用 KEK 信封加密后落库（明文 DEK 从未离开内存）
        SysDataKey fresh = new SysDataKey();
        fresh.setKeyId(newKeyId);
        fresh.setEncryptedDek(keyProvider.wrapNewDek());
        fresh.setStatus(DataKeyStatus.ACTIVE.name());
        fresh.setCreatedAt(LocalDateTime.now());
        dataKeyMapper.insert(fresh);

        // 3. 让运行时立刻切到新密钥（reload 后 currentKeyId/keyId→DEK 映射全部刷新，无需重启）
        keyProvider.reload();
        AuditTrace.append("keyId " + previousKeyId + " → " + newKeyId
                + "（新写入用新密钥；老密文仍可解，待重加密收尾后可停用旧密钥）");
        log.info("密钥轮换完成：{} → {}（旧密钥置 RETIRED，仍可解密老数据）", previousKeyId, newKeyId);
        return new RotateResult(previousKeyId, newKeyId);
    }

    /**
     * 存量数据重加密。幂等性依据：**密文自带的 keyId**（不是"密文是否相同"——
     * 密文每次加密都不同，只能看 keyId 判断新旧）。
     */
    @AuditLog(operation = "存量数据重加密", targetType = "KEY",
            detail = "轮换收尾：非活跃 keyId 的密文按批读出→解密→用当前 DEK 重加密写回（幂等/可中断/可续跑）")
    @Override
    public ReencryptResult reencrypt(int batchSize, int maxBatches) {
        int size = clamp(batchSize, DEFAULT_BATCH_SIZE, MAX_BATCH_SIZE);
        int batchesLimit = clamp(maxBatches, DEFAULT_MAX_BATCHES, MAX_BATCHES_LIMIT);
        String activeKeyId = keyProvider.currentKeyId();
        List<String> staleKeyIds = keyProvider.staleKeyIds();
        if (staleKeyIds.isEmpty()) {
            // 只有一把 ACTIVE 密钥：没有任何存量需要转换（首次部署就是这种状态）
            return new ReencryptResult(activeKeyId, 0, 0, 0, true);
        }

        String predicate = stalePredicate(staleKeyIds);
        List<Object> prefixArgs = prefixArgs(staleKeyIds);

        int batches = 0;
        int reencrypted = 0;
        long cursor = 0L;
        for (int i = 0; i < batchesLimit; i++) {
            String sql = "SELECT id, " + String.join(", ", SENSITIVE_COLUMNS)
                    + " FROM sys_employee WHERE id > ? AND (" + predicate + ") ORDER BY id LIMIT ?";
            List<Object> args = new ArrayList<>();
            args.add(cursor);
            args.addAll(prefixArgs);
            args.add(size);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());
            if (rows.isEmpty()) {
                break;
            }
            List<Map<String, Object>> batch = rows;
            // 一批一个事务：提交后再推进游标，中断时已提交的批次有效、未开始的不受影响（可续跑）
            Integer changed = transactionTemplate.execute(status -> rewriteBatch(batch, staleKeyIds));
            reencrypted += changed == null ? 0 : changed;
            batches++;
            cursor = ((Number) rows.get(rows.size() - 1).get("id")).longValue();
            if (rows.size() < size) {
                break; // 最后一批不足一整批：没有更多了
            }
        }

        long remaining = countStaleRows(predicate, prefixArgs);
        boolean allDone = remaining == 0;
        AuditTrace.append("目标密钥 " + activeKeyId + "；批次 " + batches + "；改写 " + reencrypted
                + " 行；残留 " + remaining + " 行");
        log.info("存量重加密完成：目标={} 批次={} 改写={} 残留={} allDone={}",
                activeKeyId, batches, reencrypted, remaining, allDone);
        return new ReencryptResult(activeKeyId, batches, reencrypted, remaining, allDone);
    }

    @AuditLog(operation = "停用数据加密密钥", targetType = "KEY",
            detail = "残留校验前置：还有该 keyId 的密文就拒绝停用，避免数据变成不可解密")
    @Transactional
    @Override
    public DataKeyVO disable(String keyId) {
        SysDataKey key = findOrThrow(keyId);
        DataKeyStatus status = DataKeyStatus.valueOf(key.getStatus());
        if (status == DataKeyStatus.ACTIVE) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "当前活跃密钥不能停用：请先执行密钥轮换（rotate）生成新密钥");
        }
        if (status == DataKeyStatus.DISABLED) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "密钥 " + keyId + " 已是停用状态");
        }
        // 核心校验：这就是"轮换是否彻底"的报警点。有残留时停用等于让那批数据再也读不出来，
        // 所以宁可让运维先跑完重加密，也不允许"看起来完成了"的停用。
        long remaining = countStaleRows(singlePredicate(keyId), prefixArgs(List.of(keyId)));
        if (remaining > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "密钥 " + keyId + " 仍被 " + remaining + " 行密文引用：直接停用会导致这些数据无法解密，"
                            + "请先执行重加密（POST /api/admin/keys/reencrypt）直到残留为 0");
        }
        key.setStatus(DataKeyStatus.DISABLED.name());
        dataKeyMapper.updateById(key);
        // 重新加载后该密钥不再持有密钥材料：万一还有漏网密文，第一次读取就会报错（而不是静默可解）
        keyProvider.reload();
        AuditTrace.append("keyId " + keyId + " 已停用（确认无任何密文引用，密钥材料已从内存卸载）");
        log.warn("密钥 {} 已停用（DISABLED）：不再参与解密，密钥材料已卸载", keyId);
        return toVO(key);
    }

    // ---------- 内部：批次执行 ----------

    /** 重写一批：只改写"密文里的 keyId 属于非活跃密钥"的列，其余列原样不动 */
    private int rewriteBatch(List<Map<String, Object>> rows, List<String> staleKeyIds) {
        int changed = 0;
        for (Map<String, Object> row : rows) {
            Long id = ((Number) row.get("id")).longValue();
            List<String> assignments = new ArrayList<>();
            List<Object> args = new ArrayList<>();
            for (String column : SENSITIVE_COLUMNS) {
                String stored = (String) row.get(column);
                String storedKeyId = cipher.keyIdOf(stored);
                if (storedKeyId == null || !staleKeyIds.contains(storedKeyId)) {
                    continue; // 空值 / 已经是当前密钥加密的：跳过（幂等的关键）
                }
                // 老 keyId → 用对应 DEK 解密 → 用当前活跃 DEK 重新加密（自动产生新的随机 IV）
                String reencrypted = cipher.encrypt(cipher.decrypt(stored));
                assignments.add(column + " = ?");
                args.add(reencrypted);
            }
            if (assignments.isEmpty()) {
                continue;
            }
            args.add(id);
            // updated_at = updated_at：重加密是存储层维护，不是业务数据变更，
            // 不能让"最后修改时间"被这次运维动作改掉（否则审计/业务时间线就失真了）
            jdbcTemplate.update("UPDATE sys_employee SET " + String.join(", ", assignments)
                    + ", updated_at = updated_at WHERE id = ?", args.toArray());
            changed++;
        }
        return changed;
    }

    // ---------- 内部：SQL 片段与计数 ----------

    /** 生成 "任一敏感列的密文以任一非活跃 keyId 开头" 的谓词（前缀由内部生成，无注入面） */
    private String stalePredicate(List<String> keyIds) {
        return keyIds.stream().map(this::singlePredicate).collect(Collectors.joining(" OR ", "(", ")"));
    }

    private String singlePredicate(String keyId) {
        return SENSITIVE_COLUMNS.stream()
                .map(column -> column + " LIKE ?")
                .collect(Collectors.joining(" OR ", "(", ")"));
    }

    /**
     * 与 {@link #stalePredicate} 的占位符**一一对应**的参数列表。
     *
     * 这是本周踩过的坑（BUG7-1）：`singlePredicate` 里每个密钥会生成
     * 敏感列数（4）个 `?`，参数就必须把同一个前缀重复 4 次；
     * 只传一份时 Spring 会抛 BadSqlGrammarException（参数个数不匹配），
     * 接口直接 500——错误信息只说"bad SQL grammar"，不看 SQL 里的问号个数很难反应过来。
     */
    private List<Object> prefixArgs(List<String> keyIds) {
        List<Object> args = new ArrayList<>();
        for (String keyId : keyIds) {
            String prefix = "v1:" + keyId + ":%";
            for (int i = 0; i < SENSITIVE_COLUMNS.size(); i++) {
                args.add(prefix);
            }
        }
        return args;
    }

    /** 全表统计仍引用非活跃密钥的行数（含逻辑删除行：离职档案同样要轮换干净） */
    private long countStaleRows(String predicate, List<Object> prefixArgs) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_employee WHERE (" + predicate + ")", Long.class, prefixArgs.toArray());
        return count == null ? 0 : count;
    }

    // ---------- 内部：查询与工具 ----------

    private List<SysDataKey> loadAllKeys() {
        return dataKeyMapper.selectList(new LambdaQueryWrapper<SysDataKey>().orderByAsc(SysDataKey::getId));
    }

    private SysDataKey findOrThrow(String keyId) {
        SysDataKey key = dataKeyMapper.selectOne(
                new LambdaQueryWrapper<SysDataKey>().eq(SysDataKey::getKeyId, keyId));
        if (key == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "密钥不存在：" + keyId);
        }
        return key;
    }

    /** 下一个 keyId：取现有 k{n} 的最大 n 加一（例：k1 → k2）；非 k{n} 形式的 keyId 不参与计算 */
    private String nextKeyId(List<SysDataKey> keys) {
        int max = 0;
        for (SysDataKey key : keys) {
            String id = key.getKeyId();
            if (id != null && id.matches("k\\d+")) {
                max = Math.max(max, Integer.parseInt(id.substring(1)));
            }
        }
        return "k" + (max + 1);
    }

    private int clamp(int value, int defaultValue, int max) {
        if (value <= 0) {
            return defaultValue;
        }
        return Math.min(value, max);
    }

    private DataKeyVO toVO(SysDataKey key) {
        return DataKeyVO.builder()
                .keyId(key.getKeyId())
                .status(key.getStatus())
                .dekFingerprint(keyProvider.dekFingerprint(key.getKeyId()))
                .createdAt(key.getCreatedAt())
                .retiredAt(key.getRetiredAt())
                .build();
    }
}
