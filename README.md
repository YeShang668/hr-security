# hr-security · 基于 AES 与 RBAC 的人事敏感数据安全管控系统

[![docker-regression](https://github.com/YeShang668/hr-security/actions/workflows/docker-regression.yml/badge.svg)](https://github.com/YeShang668/hr-security/actions/workflows/docker-regression.yml)

> 毕设 & 简历主项目。进度：
> ✅ 第 1 周（08-16）项目骨架 + 登录认证　✅ 第 2 周（08-23）RBAC + 部门/员工 CRUD + 接口权限
> ✅ 第 3 周（08-30）Redis 登录会话 + 权限缓存 + 数据层重构　✅ 第 4 周（09-06）Docker Compose 部署 + 场景-风险-策略调研（容器实测 31/31）
> ✅ 第 5 周（09-12）Vue3 + Element Plus 前端（`hr-ui/`）+ 用户管理接口 + 禁用踢下线（回归 56/56）
> ✅ 第 6 周（09-20）敏感字段 AES-256-GCM 加密 + 动态脱敏 + HMAC 可检索 + 幂等刷数（回归 96/96）
> ✅ 第 7 周（09-29）审计日志 AOP（异步落库）+ KEK/DEK 两级密钥 + 密钥轮换（回归 176/176）
> ✅ 第 8 周（10-03）**安全加固（越权矩阵/注入/XSS/登录防护）+ HTTPS 部署 + 前端 Docker 化 + 压测**（本地回归 228/228）
>
> 主文档（周计划/交付）见上级目录《周末项目工作前准备-*》系列；本周交付物见 `docs/`：
> - [安全加固与对抗性测试](docs/security-hardening.md)（**第 8 周**：越权矩阵表/4 处加固/已知不覆盖项）
> - [HTTPS 部署与前端容器化](docs/deployment-https.md)（**第 8 周**：唯一入口收敛/TLS 参数/自签证书/回归路径取舍）
> - [压测报告](docs/pressure-test.md)（**第 8 周**：加密与审计开销量化、原始数据、瓶颈定位）
> - [第 8 周 Bug 与踩坑记录](docs/week8-bugfix-log.md)（BUG8-1~5：GBK 假失败、MSYS 参数转换、schema 假设、断言计数）
> - [审计日志设计](docs/audit-design.md)（**论文"设计"章节底稿**：五个问题字段设计/异步取舍/@Async 三个坑/代理链 IP/已知缺口）
> - [KEK/DEK 密钥管理与轮换](docs/key-management.md)（**论文"设计"章节底稿**：为什么两级/信封加密/轮换四步/重加密实现/故障处理/取舍表）
> - [敏感字段加密与动态脱敏设计](docs/crypto-design.md)（算法参数/密文格式/TypeHandler/可检索性/脱敏规则/迁移/密钥演进/取舍）
> - [第 7 周 Bug 与踩坑记录](docs/week7-bugfix-log.md)（BUG7-1~6：SQL 占位符不匹配、测试基线失效、fail-fast 用例构造、Markdown 裸露、列宽裁切）
> - [第 6 周 Bug 与踩坑记录](docs/week6-bugfix-log.md)（BUG6-1 TypeHandler 只对写生效、BUG6-2 测试设计问题）
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
Vue3 + Element Plus 前端（hr-ui，dev 5173 / 容器内由 Nginx 托管）
   │  登录页 / 员工 / 部门 / 用户管理 / 审计日志 / 密钥管理；Axios 拦截器带 token、统一处理业务 code 与 401
   │  路由守卫按角色控制菜单与页面（前端隐藏 ≠ 安全，鉴权在后端）
   ▼  /api （dev：Vite proxy 同源转发；容器：Nginx 反代，仍是同源）
Nginx 唯一入口（第 8 周，web 容器：80 → 301 → 443）
   ├─ TLS 终止（仅 1.2/1.3 + ECDHE/AEAD）、HSTS、CSP、X-Frame-Options 等安全头
   ├─ 托管前端静态产物（多阶段构建：node 编译 → nginx 托管，运行镜像无 node_modules）
   └─ /api 反代到 app:8080，并**重置** X-Forwarded-For（只信任最外层代理写入的 IP）
Spring Boot 后端（8080，容器内不对外发布端口）
   ├─ 认证：BCrypt 密文 + JWT 24h + Redis 会话(login:token:{token} + login:user:{uid} 索引)
   │        登出即失效、服务端可踢人；禁用账号 → 删全部会话 + 角色/权限缓存（禁用即踢下线）
   ├─ 安全加固（第 8 周）：
   │    · 登录失败限流：账号 5 次 / IP 20 次 / 锁 10 分钟（Redis 计数，先于密码校验）
   │    · 注册口令强度（8~32 位含字母数字）+ 演示口令已从 123456 改为 Hr@123456
   │    · 安全响应头（nosniff / DENY / no-referrer / CSP / HSTS 仅 https）
   │    · LIKE 通配符转义（参数化只防改写 SQL，% 和 _ 是值语义，会被用来枚举全表）
   │    · 越权矩阵 21 接口 × 3 身份全格有断言（52 条安全用例）
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
   ├─ 审计与密钥（第 7 周）：
   │    · @AuditLog 注解 + AOP 切面：明文查看/权限变更/密钥运维/翻账动作全部留痕（谁·何时·做了什么·对什么·来自哪·结果）
   │    · 异步落库（@Async + 自定义线程池），用户在**请求线程先取上下文**再传参（ThreadLocal 不跨线程）
   │    · 审计 detail 只记"字段名"不记值；查询接口与前端页仅 ADMIN，单页上限 100
   │    · KEK/DEK 两级密钥：KEK 只在环境变量（不落库），DEK 用 KEK 信封加密后存 sys_data_key
   │    · 轮换四步：新 DEK 上线 → 老密文仍可解 → 分批幂等重加密 → 残留为 0 才允许停用旧 DEK
   │    · 启动自检：sys_data_key 为空则引导生成 k1；ACTIVE 的 DEK 必须恰好一把，否则拒绝启动
   └─ 部署：Docker Compose（mysql+redis+app+web），配置全环境变量化（含 AES_MASTER_KEY=KEK）
MySQL 8.4（数据） · Redis（会话/权限缓存）
```

## 运行方式（二选一 + 前端）

### 前端（第 5 周新增，需要 Node 18+）

```bash
cd hr-ui && npm install && npm run dev     # http://localhost:5173（/api 代理到 8080）
# 演示账号：admin/Hr@123456（管理员）、zhangsan/Hr@123456（普通员工）
```

### A. 本地开发（IDEA / jar）

```bash
# 1. 建库建表+种子（会 DROP 重建，root 空密码；有密码改 application-local.yml）
mysql --default-character-set=utf8mb4 -uroot < sql/init.sql
# 2. 启动：IDEA 运行 HrSecurityApplication，或：
mvn package && java -jar target/hr-security-0.1.0.jar
#    密钥来源优先级：环境变量 > 本地 application-local.yml（已 gitignore）；两处都缺启动即报错
#      JWT_SECRET     至少 32 字节（openssl rand -base64 48）
#      AES_MASTER_KEY 第 7 周起是 **KEK**（密钥加密密钥），32 字节 Base64（openssl rand -base64 32）
#                     ——它只用于信封加密 DEK（sys_data_key.encrypted_dek），不直接加密业务字段
#    首次启动若 sys_data_key 为空，会自动引导生成第一把 DEK（keyId=k1，日志有 WARN 提示）；
#    数据密钥（DEK）的轮换/重加密/停用见 docs/key-management.md，接口在 /api/admin/keys
#    连接串默认 localhost:3306/6379 root 空密码，可用 MYSQL_HOST/MYSQL_PORT/MYSQL_USER/MYSQL_PASSWORD/REDIS_HOST/REDIS_PORT 覆盖
# 3. 敏感字段加密迁移（把旧系统明文表刷成密文，幂等，可重复执行）
curl -s -X POST http://localhost:8080/api/admin/crypto/backfill -H "Authorization: Bearer $ADMIN_TOKEN"
```

> 升级须知（第 6 周 → 第 7 周）：新增 `sys_audit_log` 与 `sys_data_key` 两张表，
> 已有环境需执行建表 SQL 或重灌 `sql/init.sql`；**重灌后必须重启应用**
> （重灌只清空表，进程内仍持有旧 DEK，会出现"库里没有的 keyId"）。

### B. Docker 一键部署（第 4 周；第 8 周起唯一入口是 HTTPS）

```bash
bash docker-build/nginx/gen-cert.sh   # 第 8 周：先生成本地自签证书（web 容器挂载它；幂等）
docker compose up -d --build          # 一键起 mysql(8.4,utf8mb4,自动初始化+种子) + redis(7,AOF) + app + web(Nginx)
docker compose ps                     # 全部 healthy/Up 后访问 https://localhost（80 会 301 过来）
docker compose logs -f app            # 看应用日志（首次 MySQL 初始化需 30~60s）
docker compose down -v                # 彻底重置（删数据卷，下次 up 重新初始化；谨慎）
# 默认演示口令在 docker-compose.yml 中，生产务必通过 .env 或 export 覆盖（模板见 .env.example）
```

**第 8 周起部署形态变了（面试点）**：`web`（Nginx）是唯一对外入口（80/443），
`app` **不再对宿主发布端口**（只在内网被 `http://app:8080` 访问）——暴露面从两个入口收敛成一个。
浏览器里看不到 8080，也就不存在"绕过 Nginx 直连后端"这条路。
细节与取舍（TLS 参数、证书挂载、XFF 重置、回归为什么不走 https）见
[docs/deployment-https.md](docs/deployment-https.md)。

> 回归脚本仍需要直连后端端口，用测试专用覆盖文件把 8080 临时映射出来：
> `docker compose -f docker-compose.yml -f docker-compose.test.yml up -d --build`
> （`docker-e2e-test.sh` 已内置这两个 `-f`，不需要手工敲；理由见该文件注释）。

完整演示流程与解说词见 [docs/mvp-demo.md](docs/mvp-demo.md)。

## 测试

| 方式 | 命令 | 覆盖 |
|---|---|---|
| IDEA HTTP Client | 打开 `api-test.http` 按顺序点运行 | 手工冒烟（含第 8 周的安全加固手工验证入口） |
| **本地全量回归（推荐）** | `bash test-payloads/local-e2e-test.sh` | 一条命令跑完 **228 用例**并**逐脚本校验用例数**（自动自检环境 → 注册 zhangsan → 刷新 token → 按序执行下面七个脚本） |
| 本地分脚本 | `bash test-payloads/e2e-test.sh`（20）+ `redis-e2e-test.sh`（11）+ `user-e2e-test.sh`（25）+ `crypto-e2e-test.sh`（40）+ `audit-e2e-test.sh`（50）+ `security-e2e-test.sh`（52）+ `keyrotation-e2e-test.sh`（30） | RBAC/CRUD + Redis 会话/角色变更/登出 + 用户管理/禁用踢下线 + 加密/脱敏/可检索/迁移 + 审计埋点/异步/过滤 + **越权矩阵/注入/XSS/CSRF/爆破防护/安全头** + 密钥轮换/重加密/停用 |
| Docker 一键回归 | `bash test-payloads/docker-e2e-test.sh`（自动起容器并跑满 **240 用例**，含 HTTPS/TLS 专项与容器内"KEK 缺失启动失败"验证） | 部署后同一套用例 + `https-e2e-test.sh`（12 条：跳转/TLS 版本/证书 SAN/HSTS/反代/XFF 重置） |
| 压测（第 8 周） | `MYSQL_CMD="mysql ... -e" python test-payloads/pressure-test.py --scenario all --concurrency 8 --requests 300 --check-audit` | 登录/脱敏列表/明文读取三类接口的 QPS 与分位延迟 + **并发下审计不丢**校验（结果见 [docs/pressure-test.md](docs/pressure-test.md)） |
| 容器回归（免本机虚拟化） | GitHub Actions → workflow `docker-regression`（push 自动触发或手动 Run） | 同上；本机 Docker 不可用时的替代路径。**CI 失败时日志会自动推到 `ci-logs` 分支**（`git fetch origin ci-logs` 读失败现场，无需 GitHub 登录） |

**当前结果（2026-10-03）：本地 228/228 通过（20 + 11 + 25 + 40 + 50 + 52 + 30）；容器环境 240/240（GitHub Actions，含 12 条 HTTPS 专项）。**

- 本地实测：jar + 本机 MySQL/Redis，测试前重灌 `sql/init.sql` 并**重启应用**（让 DEK 引导重新执行）；
- **容器实测：`docker-e2e-test.sh` 已在 GitHub Actions（ubuntu runner，原生 Docker + Compose v2）跑通**，
  脚本内部有硬断言（`TOTAL_PASS != 240 || TOTAL_FAIL > 0 || 用例数不符` 即 exit 1），
  且**逐脚本校验用例数**（20/11/25/40/50/52/12/30）——因此 CI 成功 = 容器环境 240/240，且没有用例被静默跳过。
  过程说明：第 7 周的容器回归前几轮未绿，原因是**测试侧与 CI 侧**三处问题（功能本身没问题）：
  ①审计用例断言"直连 ip=回环地址"，容器里请求经 docker-proxy、对端是 Docker 网关地址（正确行为）；
  ②审计脚本漏定义辅助函数导致该用例"既没 PASS 也没 FAIL"、总数悄悄少 1；
  ③某次 run 在 `up` 阶段拉基础镜像瞬时失败且现场被 `bash -e` 吞掉。
  三处修复后 run 8 全绿，详见 `docs/week7-bugfix-log.md` BUG7-7/7-8 与 4 条 CI 观察。
- **CI 失败时的可观测性**：日志会自动推到 `ci-logs` 分支（匿名读 Actions 日志是 403、Step Summary 也要登录），
  `git fetch origin ci-logs` 后看 `meta.txt`（失败阶段）+ `docker-e2e.log`（FAIL 行）+ `compose-up.log`（启动阶段）。
- 触发方式：`.github/workflows/docker-regression.yml`（push 相关路径自动触发，也可在 Actions 页面手动 Run workflow）。
- **测试顺序有硬要求**：`keyrotation-e2e-test.sh` 会轮换密钥并停用旧密钥，**必须最后跑**；
  `audit-e2e-test.sh` 会临时禁用/改角色 zhangsan，脚本结尾自动恢复。
- **本机为什么不用 Docker 跑**（2026-09-20 记录）：本机是 Windows 11 家庭版，`HypervisorPresent = False`，
  Docker Desktop 报 "Virtualization support not detected"。诊断结论：**BIOS 虚拟化其实是开着的**
  （`VirtualizationFirmwareEnabled = True`），缺的是 Windows 侧的"虚拟机平台"功能/hypervisor 启动项
  （`wsl -d docker-desktop` 提示 "所需的虚拟化功能未启用，请启用虚拟机平台并确保固件开启虚拟化"）。
  修复（需管理员 PowerShell + 重启，不影响项目代码）：
  ```powershell
  dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart
  dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart
  bcdedit /set hypervisorlaunchtype auto
  # 重启后验证：systeminfo 出现"已检测到虚拟机监控程序"，或任务管理器→性能→CPU→虚拟化:已启用
  ```
  Docker Desktop 必须跑在 Linux 虚拟机里（WSL2 或 Hyper-V），**没有虚拟化就跑不了容器**，
  所以本机这条路的替代方案就是上面的 CI（真实 Docker 环境）——两者互为备份。

注意：
- 测试脚本依赖**干净种子数据**：本地重跑先重灌 `sql/init.sql` + 重启应用并重新注册/登录 zhangsan 刷新 token；
  Docker 环境测试会残留数据，重跑需 `docker compose down -v` 后重新 up；
- **审计是异步落库**：涉及审计的断言必须"轮询等落库"（脚本里是 `wait_sql`，最长 6s），
  不能"发完请求立刻查库"，也不能靠固定 sleep；
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
| GET | /api/audit-logs | **审计日志查询**（第 7 周，仅 ADMIN）：分页 + 按 userId/username/operation/targetType/result/时间过滤，单页上限 100 |
| GET | /api/admin/keys | **密钥列表**（第 7 周，仅 ADMIN）：keyId/状态/DEK 指纹/上线与退役时间，**不含任何密钥材料** |
| POST | /api/admin/keys/rotate | **轮换 DEK**（仅 ADMIN）：新 DEK 上线、旧 DEK 置 RETIRED（老密文仍可解） |
| POST | /api/admin/keys/reencrypt | **存量数据重加密**（仅 ADMIN，幂等可续跑）：`?batchSize=500&maxBatches=100`，返回 reencrypted/remaining/allDone |
| POST | /api/admin/keys/{keyId}/disable | **停用密钥**（仅 ADMIN）：还有残留密文时返回 400 并回报行数，为 0 才允许停用 |
| GET | /api/users | 用户分页列表（keyword/status 过滤，仅 ADMIN，出参不含密码） |
| PUT | /api/users/{id}/status | 启用/禁用账号（仅 ADMIN；禁用 = 删全部会话踢下线 + 删角色/权限缓存） |
| PUT | /api/users/{id}/roles | 覆盖式分配角色（仅 ADMIN；改完旧 token 即时生效） |
| GET | /api/roles | 全部启用角色（仅 ADMIN，供角色下拉框） |

**第 8 周在该表上体现为"行为变化"，没有新增接口**（安全加固是收紧而非扩面）：

| 接口 | 第 8 周的变化 |
|---|---|
| POST /api/auth/login | 新增**失败限流**：账号 5 次 / IP 20 次 → 锁 10 分钟，第 6 次返回业务 code **429**；登录成功/失败**都进审计**（失败详情只记"尝试了哪个账号"，绝不记口令） |
| POST /api/auth/register | 新增**口令强度**校验：8~32 位且必须同时含字母与数字（`123456`、`abcdefgh` 都会被 400 拦下，且**不落库**） |
| 所有响应 | 新增安全响应头：`X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`Referrer-Policy: no-referrer`、`Content-Security-Policy: default-src 'none'; …`；HSTS **仅在 https 请求上**下发 |
| 员工列表 /api/employees?keyword= | keyword 里的 `%`、`_`、`\` 被**转义成字面量**（加固前 `keyword=%` 会命中全表——不是注入，但能用来枚举数据） |

越权矩阵（21 个接口 × 未登录/EMPLOYEE/ADMIN，每格都有断言）见 [docs/security-hardening.md](docs/security-hardening.md)。

内置账号：`admin / Hr@123456`（ADMIN，含敏感明文权限）、`zhangsan / Hr@123456`（EMPLOYEE，需注册，无敏感明文权限）。
> 第 8 周起演示口令由 `123456` 改为 `Hr@123456`：原口令连注册入口的强度策略都过不了，
> 种子数据却留着更弱的口令属于"策略只在入口卡、内部留后门"（见 `docs/week8-bugfix-log.md` 观察 2）。
角色-权限、部门/员工表结构与种子见 `sql/init.sql`。

## 敏感数据加密与密钥管理：运维与安全注意事项

1. **密钥绝不进 git**：`AES_MASTER_KEY`（第 7 周起是 **KEK**）与 `JWT_SECRET` 都走环境变量或本地未提交的
   `application-local.yml`；两处都缺 → 应用启动即失败（fail fast，杜绝"默认密钥上线"）。
   密钥只打指纹日志（SHA-256 前 8 位），便于确认换没换密钥。
2. **两级密钥（第 7 周落地）**：KEK 只用于**信封加密 DEK**，DEK 才是加密业务字段的密钥且以密文存
   `sys_data_key`。换 DEK 不必动 KEK、不必停机，完整流程（四步 + 故障处理）见
   [docs/key-management.md](docs/key-management.md)。
3. **换 KEK = 必须重新封装 DEK**：DEK 密文头部记了 kekId，换掉 `AES_MASTER_KEY` 后启动会**明确报错**
   （而不是静默解不开）；正确做法是用原 KEK 解开 DEK → 用新 KEK 重新封装（业务数据密文不用动）。
4. **明文源要清掉**：迁移完成后 `DROP TABLE legacy_employee_plain`（或清空），否则旧表明文还在，加密白做。
5. **密文列不可模糊查**：需要按身份证查 → 用 `/api/employees/search`（哈希等值匹配）；
   需要按姓名/工号查 → 走普通 keyword（这两列不是敏感字段）。
6. **身份证哈希列有唯一索引**：哈希盐由 **KEK** 域分隔派生（与 DEK 轮换解耦）；改 KEK/盐后历史哈希全部失效，必须整列重建。
7. **密文损坏时读会失败（设计如此）**：GCM 认证标签校验不通过即报错，不静默降级返回乱码；
   修复方式是清掉坏密文后重新录入（见 `docs/week6-bugfix-log.md` BUG6-2）。
8. **停用密钥是道闸门**：DISABLED 的 DEK 不再加载密钥材料，若还有它的密文，第一次读取就报错——
   这是"轮换是否彻底"的验收信号；停用接口本身有残留校验（>0 直接拒绝）。
9. **审计 detail 只记字段名不记值**：审计表是"谁看过/改过什么"的记录，绝不能变成第二张明文敏感数据表；
   这条红线有测试固化（`audit-e2e-test.sh` A5 / C5d）。
10. **审计异步落库 = 极端情况下可能丢少量记录**：现在用内存线程池（队列满时降级为同步执行，不丢），
    生产建议换成本地消息表 + MQ 做"至少一次"投递（见 docs/audit-design.md 演进方向）。

## 代码结构

```
src/main/java/com/hrsecurity/
├── common/       # Result/ResultCode/BusinessException/GlobalExceptionHandler/PageResult/IpUtils(X-Forwarded-For 取真实客户端 IP)
├── config/       # SecurityConfig、RedisConfig、MybatisPlusConfig(分页)、MyMetaObjectHandler(自动填充)、JacksonConfig(时间格式)、
│                 #        AsyncConfig(第 7 周：@EnableAsync + auditExecutor 审计线程池)
├── security/     # JwtUtil(环境变量密钥+快速失败)、JwtAuthenticationFilter(验签→查会话→查角色+权限缓存)、LoginUser、SecurityUtils、PermissionCodes
├── crypto/       # 第 6 周：KeyProvider(密钥抽象)、AesGcmCodec(纯算法层,GCM 原语)、AesGcmCipher(AES-256-GCM 信封格式)、
│                 #        AesTypeHandler(MyBatis-Plus 自动加解密)、CryptoHolder(静态桥)、FieldHashUtil(HMAC 盲索引)、MaskingUtil(脱敏规则)
│                 # 第 7 周：KekProvider/EnvKeyProvider(KEK 来源+fail fast)、KekDekKeyProvider(信封加密 DEK/轮换/启动自检)、DataKeyStatus(状态机)
├── audit/        # 第 7 周：AuditLog(注解)、AuditLogAspect(切面,事务外层)、AuditEvent(值对象)、AuditLogRecorder/AsyncAuditLogRecorder(异步落库)、
│                 #        AuditTrace(运行时补充说明,ThreadLocal 且 finally 清理)、AuditResult(成功/失败)
├── controller/   # Auth / Dept / Employee / User / Role / CryptoAdmin(加密迁移) / AuditLog(审计查询) / KeyAdmin(密钥管理)
├── service/      # 接口 + impl（BaseServiceImpl 基类：getOrThrow 等；SessionService 维护 login:user:{uid} 会话索引；
│                 #              CryptoMigrationService 幂等刷数；AuditLogService 审计查询；
│                 #              DataKeyService 密钥轮换/重加密(TransactionTemplate 一批一事务 + JdbcTemplate 只改密文列)）
├── mapper/       # 10 个 MyBatis-Plus Mapper（审计 Mapper 刻意不提供 update/delete）
├── entity/       # sys_user/dept/employee + RBAC 四表 + sys_audit_log + sys_data_key 实体
│                 #              （@TableLogic 逻辑删除；SysEmployee 敏感字段挂 TypeHandler + autoResultMap）
└── dto/          # RegisterDTO/LoginDTO/UserVO/EmployeeVO/EmployeeSensitiveVO/BackfillResult/AuditLogVO/AuditLogQuery/
                  #              DataKeyVO/RotateResult/ReencryptResult 等

hr-ui/               # 前端工程（Vue3 + Element Plus + Pinia + Router + Axios，见 hr-ui/README.md；第 7 周新增审计日志页与密钥管理页；第 8 周新增 Dockerfile 多阶段构建）
sql/                 # 本地初始化脚本（DROP 重建；含加密列、敏感权限、模拟旧系统明文表、审计表、DEK 表）
docker-build/        # Dockerfile 用阿里云 settings + mysql 首次初始化 init.sql + nginx/（第 8 周：hr.conf + gen-cert.sh）
docker-compose.yml   # mysql+redis+app+web 编排（数据卷+healthcheck+环境变量注入；第 8 周起 app 不发布端口，唯一入口是 web 80/443）
docker-compose.test.yml      # 回归测试专用覆盖（临时把 8080 映射出来走 http，理由见文件注释）
Dockerfile           # 后端多阶段构建：maven:3.9-eclipse-temurin-17 → eclipse-temurin:17-jre
hr-ui/Dockerfile     # 前端多阶段构建（第 8 周）：node:24-alpine 构建 → nginx:alpine 托管
docs/                # security-hardening / deployment-https / pressure-test / week8-bugfix-log（第 8 周）+ audit-design / key-management / crypto-design / week7、6、5、4 bugfix / frontend-guide / scene-risk-strategy / mvp-demo
test-payloads/       # 端到端回归脚本（e2e 20 + redis 11 + user 25 + crypto 40 + audit 50 + security 52 + keyrotation 30 = 228；容器再 + https 12 = 240；pressure-test.py 为压测；token 文件不入 git）
```

## 已知演进点（面试深挖）

- 登录会话/角色缓存的面试必讲点：JWT 只做"身份凭证"，**会话与角色状态以 Redis 为准**——登出/角色变更在秒级生效，这就是第 3 周解决的问题（旧方案角色写死在 JWT claims 里，变更要等 token 过期）；
- 第 5 周把"踢下线"做成了可演示的能力：单存 `login:token:{token}` 时管理员禁用账号也拿不到"这个人手上的所有 token"，只能等 24h 过期；因此加了一层 `login:user:{uid}` 集合索引，禁用 → 遍历删除该用户全部会话 + 删角色缓存，**旧 token 下一个请求即 401**；
- 用户管理的两条自我保护红线：不能禁用自己、不能改自己的角色（否则管理员可能把自己锁在系统外）；
- 双写顺序与一致性：角色变更 = 先改库（真相源）→ 删缓存 → 下一次请求回源刷新；缓存只做 30 分钟兜底降级，不作为授权依据；
- 前端权限的正确表述：**前端隐藏菜单/按钮只是体验，权限判断在后端 `@PreAuthorize`**（同一请求在前端被守卫拦到 403 页，在后端被拦成 403 JSON，两条都有回归用例）；
- Docker 面试点：多阶段构建（构建镜像 vs 运行镜像）、数据卷（down 不丢数据）、healthcheck + depends_on（就绪顺序）、MySQL/Redis **以及第 8 周起的 app** 都不发布端口（最小暴露）、配置环境变量化（镜像可移植）；
- **第 8 周安全加固与部署（面试深挖重灾区）**，详见 [docs/security-hardening.md](docs/security-hardening.md)、[docs/deployment-https.md](docs/deployment-https.md)、[docs/pressure-test.md](docs/pressure-test.md)：
  - 为什么"防住了"和"能证明防住了"是两件事：安全交付以**越权矩阵 + 用例数 + 压测数字**为准，
    "我加了限流"远不如"5 次失败锁 10 分钟，12 条用例覆盖"；
  - 为什么限流阈值"账号 5 次 / IP 20 次"不一样：IP 维度卡太死会变成"帮攻击者锁别人账号"的**拒绝服务反噬**；
  - 为什么限流必须在**密码校验之前**：否则锁定期的正确密码照样能登录，锁等于没锁；
  - 为什么参数化之后还要转义 LIKE：`#{}` 只防"改写 SQL 结构"，而 `%`/`_` 是**值语义**，
    `keyword=%` 会命中全表（数据枚举 + 索引失效）——**参数化 ≠ 值语义安全**；
  - 为什么 XSS 不做输入过滤：过滤会破坏数据本身且列不全黑名单，正确做法是**输出编码**（Vue 插值默认转义），
    所以我们的断言是"接口返回 `application/json`"+"前端 0 处 `v-html`"而不是"把标签删掉"；
  - 为什么 CSRF 不需要 token：鉴权只认 `Authorization` 头（浏览器不自动携带），
    并用"只带 Cookie 的请求拿到 401"把这个前提**证明**出来——一旦改成 Cookie 存 token，结论立刻失效；
  - 为什么要让 Nginx **重置**（而不是追加）`X-Forwarded-For`：追加会让客户端自带的伪造链留在最左边，
    审计来源 IP 与登录限流的 IP 维度**双双可伪造**（`https-e2e-test.sh` T12 断言伪造失效）；
  - 为什么把部署形态收敛成一个入口：暴露面从"80 + 8080"变成一个"80/443"，
    后端不再对宿主发布端口，也就不存在"绕过 Nginx 直连后端"这条路；证书用只读卷挂载、私钥不进镜像；
  - 为什么要讲"回归路径的取舍"：Windows（Git Bash 的 curl 是 Schannel 后端）不认 `CURL_CA_BUNDLE`/`--cacert`，
    给 228 条用例加 `-k` 等于放弃证书校验，所以 TLS 单独拆 12 条专项用例（详见 deployment-https.md §5）；
  - 压测拿到的反直觉结论：**加密不是瓶颈**（4 字段解密 + 异步审计的业务方法平均 0.9ms），
    瓶颈是登录的 BCrypt（~65ms CPU bound，并发翻倍 QPS 翻倍而 P95 不动 → 未饱和）与列表接口的查询形态（每请求 3 条 SQL）；
    且并发 2×300 次请求**审计 300/300 零丢失**，异步设计有效。
- **第 6 周加密主线（面试深挖重灾区）**，详见 [docs/crypto-design.md](docs/crypto-design.md)：
  - 为什么 AES-256-GCM 而不是 CBC：AEAD 一次给机密性+完整性，避免"再叠 HMAC"的实现坑（padding oracle）；
  - 为什么 IV 必须每次随机：GCM 下 IV 复用会泄露明文异或关系并让认证密钥被恢复，属致命错误；
  - 为什么用 TypeHandler：不遗漏、不重复、实体语义清晰；代价是密文列不能 like，于是有了盲索引；
  - 为什么可检索性用 HMAC-SHA256 而非裸 SHA-256：身份证空间有限，裸哈希可被彩虹表反推；
  - 为什么明文出口只有一个：把"看明文"做成显式动作，审计只需盯一处（下周 AOP 挂这里）；
  - 为什么密钥必须 fail fast：有默认密钥就一定会有人带着默认密钥上线，等于全部密文可解；
  - 为什么迁移用应用侧代码而不是 SQL 脚本：脚本会复制加密逻辑，一旦与程序不一致就会出现"一半数据解不开"。
- **第 7 周审计与密钥（面试深挖重灾区）**，详见 [docs/audit-design.md](docs/audit-design.md) 与 [docs/key-management.md](docs/key-management.md)：
  - 为什么审计要冗余存 username：用户改名/注销后仍要能回答"当时是谁"；
  - 为什么切面要放在**事务外层**：确保记录的是"真正提交成功"的结果，且失败记录不会被业务事务回滚牵连；
  - 为什么 `@Async` 必须自定义线程池 + `CallerRunsPolicy`：默认执行器每次新建线程不限并发；队列满时宁可降级为同步也不丢审计；
  - 为什么上下文要在切面里先取：`SecurityContextHolder`/`RequestContextHolder` 都是 ThreadLocal，异步线程取到 null；
  - 为什么审计 detail 不能记值：否则审计表变成第二张明文敏感数据表；
  - 为什么 `@TableLogic` 会让重加密漏数据：离职（逻辑删除）行用实体 API 读写会被自动加 `status=1` 条件，
    所以重加密改用 JdbcTemplate 直接按密文列前缀操作，并显式 `updated_at = updated_at` 不改业务时间；
  - 为什么停用密钥要有残留校验：还有老密文就停用 = 那批数据永远解不开；DISABLED 后连密钥材料都不加载，问题会立刻暴露；
  - 为什么 KEK 与检索哈希盐绑定、与 DEK 解耦：DEK 轮换时 `id_card_hash` 不能失效（否则要全量重建索引）；
  - 为什么"业务代码零改动"是设计胜利：第 6 周抽 `KeyProvider` 接口 → 第 7 周只换实现类，加解密器/TypeHandler/Service 一行未改。
