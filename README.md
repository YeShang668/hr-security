# hr-security · 基于 AES 与 RBAC 的人事敏感数据安全管控系统

> 毕设 & 简历主项目。进度：
> ✅ 第 1 周（08-16）项目骨架 + 登录认证　✅ 第 2 周（08-23）RBAC + 部门/员工 CRUD + 接口权限
> ✅ 第 3 周（08-30）Redis 登录会话 + 权限缓存 + 数据层重构　✅ 第 4 周（09-06）Docker Compose 部署 + 场景-风险-策略调研（容器实测 31/31）
> ✅ 第 5 周（09-12）Vue3 + Element Plus 前端（`hr-ui/`）+ 用户管理接口 + 禁用踢下线（回归 56/56）
> ✅ 第 6 周（09-20）**敏感字段 AES-256-GCM 加密 + 动态脱敏 + HMAC 可检索 + 幂等刷数**（回归 96/96）
>
> 主文档（周计划/交付）见上级目录《周末项目工作前准备-*》系列；本周交付物见 `docs/`：
> - [敏感字段加密与动态脱敏设计](docs/crypto-design.md)（**论文"设计"章节底稿**：算法参数/密文格式/TypeHandler/可检索性/脱敏规则/迁移/密钥演进/取舍）
> - [第 6 周 Bug 与踩坑记录](docs/week6-bugfix-log.md)（BUG6-1 TypeHandler 只对写生效、BUG6-2 测试设计问题、脱敏策略中途收紧的观察）
> - [前端工程说明](docs/frontend-guide.md)（技术选型/目录/守卫与拦截器设计/3 分钟演示剧本）
> - [第 5 周 Bug 修复记录](docs/week5-bugfix-log.md)（BUG5-1~5，含 401 清态漏清 Pinia、Jackson 时间格式等）
> - [场景-风险-策略调研小结](docs/scene-risk-strategy.md)（论文"设计"章节话术底稿）
> - [MVP 演示脚本](docs/mvp-demo.md)（Docker 一键起 → 演示 → 收尾全流程）
> - [第 4 周 Bug 修复记录](docs/week4-bugfix-log.md)
>
> 前端工程在 [`hr-ui/`](hr-ui/README.md)（`npm run dev` → http://localhost:5173）。

## 技术栈

后端：Spring Boot 3.5 · Spring Security 6 · MyBatis-Plus 3.5 · MySQL 8.4 · Redis 7 · JWT (jjwt 0.12) · **AES-256-GCM（JDK 自带 `javax.crypto`）** · Docker Compose · JDK 17+（本机 JDK 25 亦可，字节码按 17 编译）
前端：Vue 3 · Vite · Element Plus · Pinia · Vue Router · Axios（第 5 周）

## 架构与安全主线

```
Vue3 + Element Plus 前端（hr-ui，5173）
   │  登录页 / 员工 / 部门 / 用户管理；Axios 拦截器带 token、统一处理业务 code 与 401
   │  路由守卫按角色控制菜单与页面（前端隐藏 ≠ 安全，鉴权在后端）
   ▼  /api （Vite dev proxy 同源转发）
Spring Boot 后端（8080）
   ├─ 认证：BCrypt 密文 + JWT 24h + Redis 会话(login:token:{token} + login:user:{uid} 索引)
   │        登出即失效、服务端可踢人；禁用账号 → 删全部会话 + 角色/权限缓存（禁用即踢下线）
   ├─ 权限：RBAC 最小权限 + 权限编码（employee:sensitive:read 等）；角色与权限存 Redis
   │        缓存(user:roles:{id} / user:perms:{id})，不信任 JWT claims，变更删缓存即时生效
   ├─ 用户管理：用户分页/启用禁用/角色分配（仅 ADMIN）+ 管理员自我保护（不能禁用自己、不能改自己角色）
   ├─ 数据：部门/员工 CRUD + @TableLogic 离职逻辑删除 + MetaObjectHandler 自动填充
   ├─ 敏感数据（第 6 周）：
   │    · 字段级 AES-256-GCM（随机 12 字节 IV + 128 位认证标签），密文格式 v1:{keyId}:{iv}:{ct}
   │    · MyBatis-Plus TypeHandler 自动加解密（业务代码只见明文，库里只有密文）
   │    · 列出参统一动态脱敏（连 ADMIN 也脱敏），明文只走 /api/employees/{id}/sensitive 显式出口
   │    · 身份证另存 HMAC-SHA256 盲索引 → 精确检索 + 唯一校验（密文不可 like）
   │    · 历史数据幂等刷数（旧系统明文表 → 一键加密迁移，可重复执行）
   │    · 下一步：KEK/DEK 密钥轮换 + 审计日志 AOP（9/26）
   └─ 部署：Docker Compose（mysql+redis+app），配置全环境变量化（含 AES_MASTER_KEY）
MySQL 8.4（数据） · Redis（会话/权限缓存）
```

## 运行方式（二选一 + 前端）

### 前端（第 5 周新增，需要 Node 18+）

```bash
cd hr-ui && npm install && npm run dev     # http://localhost:5173（/api 代理到 8080）
# 演示账号：admin/123456（管理员）、zhangsan/123456（普通员工）
```

### A. 本地开发（IDEA / jar）

```bash
# 1. 建库建表+种子（会 DROP 重建，root 空密码；有密码改 application-local.yml）
mysql --default-character-set=utf8mb4 -uroot < sql/init.sql
# 2. 启动：IDEA 运行 HrSecurityApplication，或：
mvn package && java -jar target/hr-security-0.1.0.jar
#    密钥来源优先级：环境变量 > 本地 application-local.yml（已 gitignore）；两处都缺启动即报错
#      JWT_SECRET     至少 32 字节（openssl rand -base64 48）
#      AES_MASTER_KEY 敏感字段加密主密钥，必须 32 字节 Base64（openssl rand -base64 32）
#    连接串默认 localhost:3306/6379 root 空密码，可用 MYSQL_HOST/MYSQL_PORT/MYSQL_USER/MYSQL_PASSWORD/REDIS_HOST/REDIS_PORT 覆盖
# 3. 敏感字段加密迁移（把旧系统明文表刷成密文，幂等，可重复执行）
curl -s -X POST http://localhost:8080/api/admin/crypto/backfill -H "Authorization: Bearer $ADMIN_TOKEN"
```

### B. Docker 一键部署（第 4 周，需要 Docker Desktop）

```bash
docker compose up -d --build     # 一键起 mysql(8.4,utf8mb4,自动初始化+种子) + redis(7,AOF) + app
docker compose ps                # 全部 healthy/Up 后访问 http://localhost:8080
docker compose logs -f app       # 看应用日志（首次 MySQL 初始化需 30~60s）
docker compose down -v           # 彻底重置（删数据卷，下次 up 重新初始化；谨慎）
# 默认演示口令在 docker-compose.yml 中，生产务必通过 .env 或 export 覆盖（模板见 .env.example）
```

完整演示流程与解说词见 [docs/mvp-demo.md](docs/mvp-demo.md)。

## 测试

| 方式 | 命令 | 覆盖 |
|---|---|---|
| IDEA HTTP Client | 打开 `api-test.http` 按顺序点运行 | 手工冒烟 |
| 本地全量回归 | `bash test-payloads/e2e-test.sh`（20）+ `bash test-payloads/redis-e2e-test.sh`（11）+ `bash test-payloads/user-e2e-test.sh`（25）+ `bash test-payloads/crypto-e2e-test.sh`（40） | RBAC/CRUD + Redis 会话/角色变更/登出 + 用户管理/禁用踢下线 + **加密/脱敏/可检索/迁移** |
| Docker 一键回归 | `bash test-payloads/docker-e2e-test.sh`（自动起容器并跑满 96 用例） | 部署后同一套用例全量回归 |

**当前结果（2026-09-20 本地 jar 实测）：96/96 通过**（20 + 11 + 25 + 40）。
说明：`docker-e2e-test.sh` 已同步纳入 crypto 专项（预期 96）；容器复测结果以脚本实际输出为准（见文末"第 6 周容器复测"小节）。

注意：
- 测试脚本依赖**干净种子数据**：本地重跑先重灌 `sql/init.sql` 并重新注册/登录 zhangsan 刷新 token；Docker 环境测试会残留数据，重跑需 `docker compose down -v` 后重新 up；
- `crypto-e2e-test.sh` 自带清理（开头删 E200/E201，C7 会把 E001~E003 的密文清空后重迁移），可重复运行；
- 脚本需要 python 解释器解析 JSON；找不到会明确报错退出，也可用 `PYTHON=/path/to/python bash xxx.sh` 指定（Git Bash 下 `python` 可能被 Windows 应用别名占位，见 BUG5-4）；
- 接口测试脚本里中文请求体走 UTF-8 临时文件（Git Bash 内联中文会变 GBK）；
- 业务错误（400/403/409/500）HTTP 码都是 200，需解析响应体 `code` 字段；只有 Security 的 401 才是 HTTP 401；
- 加密脚本需要直连 MySQL 断言密文，默认用本机 mysql 客户端；Docker 环境由 `MYSQL_CMD` 改成 `docker compose exec` 形态。

## 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/auth/register | 注册（BCrypt 存密码，默认 EMPLOYEE 角色） |
| POST | /api/auth/login | 登录，返回 JWT（24h）+ 写 Redis 会话 |
| POST | /api/auth/logout | 登出（删 Redis 会话，旧 token 立即失效） |
| GET | /api/auth/me | 当前用户（含最新 roles） |
| GET/POST/PUT/DELETE | /api/depts、/api/depts/{id} | 部门 CRUD（管理操作仅 ADMIN） |
| GET/POST/PUT/DELETE | /api/employees、/api/employees/{id} | 员工 CRUD：分页+keyword+部门名联查；管理操作仅 ADMIN；**敏感字段出参一律脱敏** |
| GET | /api/employees/{id}/sensitive | **敏感信息明文**（身份证/手机号/银行卡/工资），需 `employee:sensitive:read` 权限（默认仅 ADMIN） |
| GET | /api/employees/search?idCard=xxx | **按身份证精确查**（密文不可 like，走 HMAC 盲索引等值匹配），需同权限 |
| POST | /api/admin/crypto/backfill | **历史数据加密迁移**（幂等刷数，仅 ADMIN，返回 scanned/migrated/skipped/unmatched） |
| GET | /api/users | 用户分页列表（keyword/status 过滤，仅 ADMIN，出参不含密码） |
| PUT | /api/users/{id}/status | 启用/禁用账号（仅 ADMIN；禁用 = 删全部会话踢下线 + 删角色/权限缓存） |
| PUT | /api/users/{id}/roles | 覆盖式分配角色（仅 ADMIN；改完旧 token 即时生效） |
| GET | /api/roles | 全部启用角色（仅 ADMIN，供角色下拉框） |

内置账号：`admin / 123456`（ADMIN，含敏感明文权限）、`zhangsan / 123456`（EMPLOYEE，需注册，无敏感明文权限）。角色-权限、部门/员工表结构与种子见 `sql/init.sql`。

## 敏感数据加密：运维与安全注意事项

1. **密钥绝不进 git**：`AES_MASTER_KEY`（32 字节 Base64）与 `JWT_SECRET` 都走环境变量或本地未提交的
   `application-local.yml`；两处都缺 → 应用启动即失败（fail fast，杜绝"默认密钥上线"）。
   密钥只打指纹日志（SHA-256 前 8 位），便于确认换没换密钥。
2. **换密钥 = 数据不可解**：密文里写了 `keyId`（如 `k1`），轮换必须"新旧密钥并存 + 后台重加密"，
   下周（第 7 周）做 KEK/DEK 时落地。
3. **明文源要清掉**：迁移完成后 `DROP TABLE legacy_employee_plain`（或清空），否则旧表明文还在，加密白做。
4. **密文列不可模糊查**：需要按身份证查 → 用 `/api/employees/search`（哈希等值匹配）；
   需要按姓名/工号查 → 走普通 keyword（这两列不是敏感字段）。
5. **身份证哈希列有唯一索引**：改哈希盐/主密钥后历史哈希全部失效，必须整列重建。
6. **密文损坏时读会失败（设计如此）**：GCM 认证标签校验不通过即报错，不静默降级返回乱码；
   修复方式是清掉坏密文后重新录入（见 `docs/week6-bugfix-log.md` BUG6-2）。

## 代码结构

```
src/main/java/com/hrsecurity/
├── common/       # Result/ResultCode/BusinessException/GlobalExceptionHandler/PageResult
├── config/       # SecurityConfig、RedisConfig、MybatisPlusConfig(分页)、MyMetaObjectHandler(自动填充)、JacksonConfig(时间格式)
├── security/     # JwtUtil(环境变量密钥+快速失败)、JwtAuthenticationFilter(验签→查会话→查角色+权限缓存)、LoginUser、SecurityUtils、PermissionCodes
├── crypto/       # 第 6 周：KeyProvider/EnvKeyProvider(密钥来源+fail fast)、AesGcmCipher(AES-256-GCM)、
│                 #        AesTypeHandler(MyBatis-Plus 自动加解密)、CryptoHolder(静态桥)、FieldHashUtil(HMAC 盲索引)、MaskingUtil(脱敏规则)
├── controller/   # Auth / Dept / Employee / User / Role / CryptoAdmin(加密迁移)
├── service/      # 接口 + impl（BaseServiceImpl 基类：getOrThrow 等；SessionService 维护 login:user:{uid} 会话索引；
│                 #              CryptoMigrationService 幂等刷数）
├── mapper/       # 8 个 MyBatis-Plus Mapper
├── entity/       # sys_user/dept/employee + RBAC 四表实体（@TableLogic 逻辑删除；SysEmployee 敏感字段挂 TypeHandler + autoResultMap）；
│                 #              LegacyEmployeePlain（模拟旧系统明文表，仅迁移用）
└── dto/          # RegisterDTO/LoginDTO/UserVO/EmployeeVO/EmployeeSensitiveVO/BackfillResult 等

hr-ui/               # 前端工程（Vue3 + Element Plus + Pinia + Router + Axios，见 hr-ui/README.md）
sql/                 # 本地初始化脚本（DROP 重建；含加密列、敏感权限、模拟旧系统明文表）
docker-build/        # Dockerfile 用阿里云 settings + mysql 首次初始化 init.sql
docker-compose.yml   # mysql+redis+app 编排（数据卷+healthcheck+环境变量注入，含 AES_MASTER_KEY）
Dockerfile           # 多阶段构建：maven:3.9-eclipse-temurin-17 → eclipse-temurin:17-jre
docs/                # crypto-design / week6-bugfix-log / frontend-guide / scene-risk-strategy / mvp-demo / week5、week4 bugfix
test-payloads/       # 端到端回归脚本（e2e 20 + redis 11 + user 25 + crypto 40；token 文件不入 git）
```

## 已知演进点（面试深挖）

- 登录会话/角色缓存的面试必讲点：JWT 只做"身份凭证"，**会话与角色状态以 Redis 为准**——登出/角色变更在秒级生效，这就是第 3 周解决的问题（旧方案角色写死在 JWT claims 里，变更要等 token 过期）；
- 第 5 周把"踢下线"做成了可演示的能力：单存 `login:token:{token}` 时管理员禁用账号也拿不到"这个人手上的所有 token"，只能等 24h 过期；因此加了一层 `login:user:{uid}` 集合索引，禁用 → 遍历删除该用户全部会话 + 删角色缓存，**旧 token 下一个请求即 401**；
- 用户管理的两条自我保护红线：不能禁用自己、不能改自己的角色（否则管理员可能把自己锁在系统外）；
- 双写顺序与一致性：角色变更 = 先改库（真相源）→ 删缓存 → 下一次请求回源刷新；缓存只做 30 分钟兜底降级，不作为授权依据；
- 前端权限的正确表述：**前端隐藏菜单/按钮只是体验，权限判断在后端 `@PreAuthorize`**（同一请求在前端被守卫拦到 403 页，在后端被拦成 403 JSON，两条都有回归用例）；
- Docker 面试点：多阶段构建（构建镜像 vs 运行镜像）、数据卷（down 不丢数据）、healthcheck + depends_on（就绪顺序）、MySQL/Redis 不发布端口（最小暴露）、配置环境变量化（镜像可移植）；
- **第 6 周加密主线（面试深挖重灾区）**，详见 [docs/crypto-design.md](docs/crypto-design.md)：
  - 为什么 AES-256-GCM 而不是 CBC：AEAD 一次给机密性+完整性，避免"再叠 HMAC"的实现坑（padding oracle）；
  - 为什么 IV 必须每次随机：GCM 下 IV 复用会泄露明文异或关系并让认证密钥被恢复，属致命错误；
  - 为什么用 TypeHandler：不遗漏、不重复、实体语义清晰；代价是密文列不能 like，于是有了盲索引；
  - 为什么可检索性用 HMAC-SHA256 而非裸 SHA-256：身份证空间有限，裸哈希可被彩虹表反推；
  - 为什么明文出口只有一个：把"看明文"做成显式动作，审计只需盯一处（下周 AOP 挂这里）；
  - 为什么密钥必须 fail fast：有默认密钥就一定会有人带着默认密钥上线，等于全部密文可解；
  - 为什么迁移用应用侧代码而不是 SQL 脚本：脚本会复制加密逻辑，一旦与程序不一致就会出现"一半数据解不开"。
