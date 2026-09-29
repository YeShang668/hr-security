# KEK/DEK 两级密钥与轮换设计（第 7 周）

> 用途：论文"设计"章节里"密钥管理"部分的底稿；面试"密钥怎么管"的标准答案。
> 配套文档：`crypto-design.md`（字段加密与脱敏）、`audit-design.md`（审计）。

## 1. 为什么需要两级密钥（先讲清楚"不用两级会怎样"）

假设只用一把密钥加密全库敏感字段（第 6 周的形态）：

| 问题 | 只有一把密钥时 | 两级密钥时 |
|---|---|---|
| 换密钥成本 | 必须解密全库 + 重加密全库，期间要么停机、要么写双份 | **换 DEK 不用动 KEK**，新数据用新密钥，老数据慢慢转（不停机） |
| 泄露面 | 密钥泄露 = 全库数据可解 | 数据库被拖走只拿到 **DEK 密文**，没有 KEK 依然解不开 |
| 密钥可用性 | 密钥丢了数据全完，且必须单独备份这把密钥 | DEK 密文就在库里，用 KEK 随时可恢复 |
| 职责分离 | 运维看得到"数据密钥"，等于看得到数据 | **KEK 交给 KMS/环境变量**，运维日常接触不到；数据库账号拿到 DEK 密文也无用 |

一句话：**KEK 保护 DEK，DEK 保护数据**（信封加密，Envelope Encryption）。

## 2. 结构

```
KEK（AES_MASTER_KEY 环境变量 / 生产用 KMS，永不落库、不进 git）
  │  用 KEK 域分隔派生出"包装子密钥"（SHA-256("hr-security:kek-wrap-dek:v1" + KEK)）
  └─▶ 用包装子密钥 AES-256-GCM 加密 DEK → encrypted_dek 存入 sys_data_key（v1:{kekId}:{iv}:{ct}）
        └─▶ DEK（32 字节随机，明文只在应用内存中）
              └─▶ 加密业务字段 → 密文 v1:{keyId}:{iv}:{ct}（与第 6 周格式一致）
```

关键点：

- **DEK 只以密文形式落库**。测试断言 `encrypted_dek` 的结构为 IV 12 字节 + 密文 48 字节
  （= 32 字节 DEK + 16 字节 GCM 认证标签），并且库里查不到 KEK 字符串
  （`keyrotation-e2e-test.sh` K2~K5）。
- **KEK 为什么沿用 `AES_MASTER_KEY` 这个名字**：第 6 周它直接是数据密钥，第 7 周起语义变成 KEK。
  沿用是为了兼容既有部署脚本，也避免"多一个环境变量就多一个忘配置的机会"；
  文档与日志里一律称 KEK。
- **域分隔**：不拿 KEK 直接当加密密钥，而是派生一个带专用标签的子密钥去包装 DEK。
  同一根密钥在不同用途下派生子密钥是密码学惯例（跨用途复用密钥是很多真实事故的根因）。
- **`sys_data_key` 不预置种子行**：`encrypted_dek` 与当前环境的 KEK 绑定，
  写死在 `init.sql` 里等于把密钥材料提交进 git（红线），换个 KEK 也再也解不开。
  首次启动由 `KekDekKeyProvider` 引导生成第一把 DEK（日志 WARN 提示）。

## 3. 业务代码零改动（"先抽接口"的收益）

第 6 周把"密钥从哪来"抽成 `KeyProvider` 接口，当时只有 `EnvKeyProvider` 一个实现。
第 7 周新增 `KekDekKeyProvider` 实现，**只是换了一个 Spring 组件**：

| 组件 | 第 6 周 | 第 7 周 |
|---|---|---|
| `AesGcmCipher`（加解密器） | 依赖 `KeyProvider` | **一行未改** |
| `AesTypeHandler`（字段自动加解密） | 调 `CryptoHolder.cipher()` | **一行未改** |
| 所有 Service / Controller | 不感知密钥 | **一行未改** |
| `EnvKeyProvider` | `implements KeyProvider`（数据密钥） | 改为 `implements KekProvider`（只做 KEK 来源） |
| `KekDekKeyProvider` | — | 新增：`implements KeyProvider`，信封加密 + 轮换支持 |

对照组：如果第 6 周把 `EnvKeyProvider` 直接写进 `AesGcmCipher` 里，这次就得改加解密器本身，
并且要重新验证所有加密路径。**这就是"面向接口抽一层"在真实需求变化时的价值，可以直接当答辩素材。**

顺带一个设计细节：算法原语下沉到了 `AesGcmCodec`（静态工具类）。
原因很实际——`AesGcmCipher` 依赖 `KeyProvider`，而 `KekDekKeyProvider` 又要用 GCM 加密 DEK，
若复用它就会形成 `AesGcmCipher → KeyProvider → AesGcmCipher` 的循环依赖。
把"给定密钥 + IV 的加解密"抽成无依赖的静态方法，两边共用一份实现，
也顺带保证 IV 长度、标签长度这类参数不会两处各写一份而写歪。

## 4. 启动自检（fail fast 的两条）

`KekDekKeyProvider#init`：

1. **`sys_data_key` 为空** → 引导生成第一把 DEK（新环境/首次部署的正常路径，日志用 WARN 提醒：
   "生产环境请确认这是预期动作"）；
2. **ACTIVE 的 DEK 必须恰好一把** → 否则拒绝启动：
   - 0 把：没有密钥能加密新数据；
   - ≥2 把：到底用哪把加密？这种"看起来能用"的状态最危险。

   宁可启动失败，也不要带着不确定的密钥体系对外服务（与 `JWT_SECRET`/KEK 缺失即失败同一原则）。

另外两条防退化设计：

- **KEK 缺失 → 启动失败**：绝不用"默认密钥"静默跑起来（有默认密钥就一定会有人带着它上线，
  等于全库密文可解，比不加密更危险——虚假的安全感）。实测断言见 K28：
  在"读不到本地配置、且显式清空 `AES_MASTER_KEY`"的环境启动，进程必须非 0 退出并提示 `AES_MASTER_KEY`。
- **换过 KEK → 明确报错**：DEK 密文头部记了 `kekId`，当前 KEK 与它不匹配时抛
  "DEK 是用 KEK xxx 加密的，当前是 yyy" ——而不是抛一个看不懂的 GCM 解密失败。

## 5. 轮换流程（四步闭环）

| 步 | 动作 | 接口 | 关键性质 |
|---|---|---|---|
| 1 | **生成新 DEK 并置 ACTIVE**，旧 DEK 置 RETIRED（记 `retired_at`） | `POST /api/admin/keys/rotate` | 新写入立刻用新密钥；**老密文仍可解**，业务零停机 |
| 2 | **存量数据分批重加密** | `POST /api/admin/keys/reencrypt` | 幂等、可中断、可续跑；跑到 `remaining=0` |
| 3 | **校验残留** | 同上返回值 | `remaining` 是"还有几行仍引用老密钥"的硬指标 |
| 4 | **停用旧 DEK** | `POST /api/admin/keys/{keyId}/disable` | **有残留则拒绝**（并回报还有几行），允许后卸载密钥材料 |

状态机：`ACTIVE（新数据用它）→ RETIRED（不再加密，仍能解密）→ DISABLED（拒绝解密）`。

为什么 RETIRED 不能直接删：删掉就再也解不开老密文了。轮换期间必须容忍"新老密文并存"。

### 5.1 重加密是怎么实现的（三个刻意的选择）

```sql
-- 找：按密文列前缀匹配"老 keyId"
SELECT id, phone_enc, id_card_enc, bank_card_enc, salary_enc FROM sys_employee
 WHERE id > ? AND (phone_enc LIKE ? OR id_card_enc LIKE ? OR bank_card_enc LIKE ? OR salary_enc LIKE ?)
 ORDER BY id LIMIT ?
```
逐列判断密文自带的 keyId 是不是老密钥，是则 `解密（用老 DEK）→ 重新加密（用当前 DEK）` 写回。

1. **用 JdbcTemplate 直接操作列，而不是走 MyBatis-Plus 实体更新**（关键决策）：
   - MP 的 `selectById/updateById` 受实体上 `@TableLogic` 影响，会自动带 `status = 1` 条件，
     于是**已离职（逻辑删除）员工的密文永远轮换不到**——"全库不存在老 keyId 密文"这条验收线
     会一直不通过，而离职档案同样是需要保护的敏感数据（测试 K13 专门断言"含离职员工 count=0"）；
   - 走实体会整行回写（含所有业务字段），而重加密本质是**存储层维护**，只应该碰密文列；
   - `UPDATE ... SET {密文列}, updated_at = updated_at`：**不篡改"最后修改时间"**——
     重加密不是业务数据变更，让 `updated_at` 跳一下会让业务时间线失真。
2. **批次 + 每批一个独立事务**：不分批会有两个后果——一个大事务锁住大量行、undo log 膨胀；
   中途失败要全部重来。用 `TransactionTemplate` 编程式事务（一批一提交），
   同时避开"同类方法自调用导致 `@Transactional` 失效"的经典坑。
   批次之间用 **id 游标**（`id > lastId`）推进，保证每批往前走、不会因为个别行解不开而原地死循环。
3. **幂等依据是"密文自带的 keyId"**，不是"密文是否相同"——密文每次加密都不同（IV 随机），
   只能看 keyId 判断新旧。第二次执行 `reencrypted=0`（测试 K14）。

### 5.2 DISABLED 是一道"报警闸门"

停用后 `key(keyId)` 直接抛异常（密钥材料已从内存卸载），于是：

- "还有老密文没转完就停用" → 第一次读到那些数据就报错，**问题立刻暴露**，而不是静默可解；
- 停用接口本身先做残留校验，`remaining > 0` 时返回 400 并告知还有几行——把事故拦在动作之前。

测试 K18 手工把一条真正的 k1 密文写回库，断言读取**失败**（而不是返回乱码/明文），
再把现场恢复（K19），完整演示了这道闸门。

## 6. 运维视角：一次完整的轮换怎么做

```bash
# 0. 观察现状（ACTIVE 是谁、有没有 RETIRED 挂着）
curl -s $API/api/admin/keys -H "Authorization: Bearer $ADMIN"

# 1. 轮换：新 DEK 上线（老数据此刻仍然读得出来，业务不中断）
curl -s -X POST $API/api/admin/keys/rotate -H "Authorization: Bearer $ADMIN"
# → {"previousKeyId":"k1","newKeyId":"k2"}

# 2. 存量重加密（可反复执行；数据量大时多次调用续跑）
curl -s -X POST "$API/api/admin/keys/reencrypt?batchSize=500&maxBatches=100" -H "Authorization: Bearer $ADMIN"
# → {"activeKeyId":"k2","batches":1,"reencrypted":6,"remaining":0,"allDone":true}

# 3. 残留为 0 才能停用旧密钥（还有残留会 400，并回报行数）
curl -s -X POST $API/api/admin/keys/k1/disable -H "Authorization: Bearer $ADMIN"
```

前端「密钥管理」页把这四步做成了步骤条 + 两个按钮（轮换 / 执行重加密）+ 残留校验提示，
演示时可直接点；页面**看不到任何密钥材料**，只有 keyId、状态、DEK 指纹（SHA-256 前 8 位）与时间。

## 7. 故障处理

| 现象 | 原因 | 处理 |
|---|---|---|
| 启动报"KEK 未配置" | 环境变量 `AES_MASTER_KEY` 缺失且无本地配置 | 注入 KEK（生产从 KMS/密钥管理服务取），**不要**临时改成默认值 |
| 启动报"ACTIVE 的 DEK 必须恰好一把" | 人为改过 `sys_data_key` 状态 | 修正状态：确保只有一把 ACTIVE；若确实要换密钥走 rotate 接口 |
| 启动报"DEK 是用 KEK xxx 加密的" | 换过 `AES_MASTER_KEY` | 用**原 KEK** 启动，解密后用新 KEK 重新封装 DEK（或用原 KEK 解开全部数据再换），见下条 |
| 读取报"密文完整性校验失败" | 密文被篡改 / 密钥不匹配 | 定位该行数据来源，用备份恢复；不要试图"跳过" |
| 读取报"密钥已停用（DISABLED）" | 还有该作者密文残留就停用了 | 说明轮换没做完：临时把该密钥置回 RETIRED 恢复可读，跑完重加密再停用 |
| 想彻底换 KEK（不只是换 DEK） | KEK 泄露或合规要求 | 步骤：①用原 KEK 解开所有 DEK ②用新 KEK 重新封装 `encrypted_dek` 并更新 `kekId` ③换环境变量重启。**业务数据密文不用动**——这正是两级结构的好处 |

> 关于"想彻底换 KEK"：真实生产建议在 KMS 里做密钥版本化（同一 keyId 保留多版本 KEK），
> 本项目用环境变量模拟，因此实现成"离线重封装"；论文里说明该差异即可。

## 8. 已知取舍与后续演进

| 取舍 | 现状 | 生产更强做法 |
|---|---|---|
| KEK 存储 | 环境变量（便于本地演示与 CI） | KMS/HSM 托管，支持密钥版本与审计，主密钥不出硬件 |
| DEK 明文缓存 | 启动时解密进内存，常驻（ACTIVE/RETIRED 可解，DISABLED 不加载） | 内存加密、按需解密 + 短缓存；或交给加密库（如 Vault Transit） |
| 重加密触发 | ADMIN 手动调接口（可演示、可观测） | 定时任务/消息队列驱动，进度可观测、失败可重试 |
| 多实例一致性 | 单实例场景：`reload()` 后本进程立即生效 | 多实例需要广播失效（Redis Pub/Sub）或每次加密前查库拿 ACTIVE keyId |
| 停用与压缩 | DISABLED 的 DEK 密文仍留在表里（用于解释历史） | 保留审计意义上的"密钥履历"，不物理删除 |
| `id_card_hash` 与密钥 | 哈希盐由 **KEK** 派生，与 DEK 轮换解耦 | 同思路：检索索引用独立的密钥/盐，避免与数据密钥耦合导致轮换时全量重建索引 |
