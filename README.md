# hr-security · 基于 AES 与 RBAC 的人事敏感数据安全管控系统

> 毕设 & 简历主项目。进度：
> ✅ 第 1 周（08-16）项目骨架 + 登录认证　✅ 第 2 周（08-23）RBAC + 部门/员工 CRUD + 接口权限
> ✅ 第 3 周（08-30）Redis 登录会话 + 权限缓存 + 数据层重构　✅ 第 4 周（09-06）Docker Compose 部署 + 场景-风险-策略调研
>
> 主文档（周计划/交付）见上级目录《周末项目工作前准备-*》系列；本周交付物见 `docs/`：
> - [场景-风险-策略调研小结](docs/scene-risk-strategy.md)（论文"设计"章节话术底稿）
> - [MVP 演示脚本](docs/mvp-demo.md)（Docker 一键起 → 演示 → 收尾全流程）
> - [第 4 周 Bug 修复记录](docs/week4-bugfix-log.md)

## 技术栈

Spring Boot 3.5 · Spring Security 6 · MyBatis-Plus 3.5 · MySQL 8.4 · Redis 7 · JWT (jjwt 0.12) · Docker Compose · JDK 17+（本机 JDK 25 亦可，字节码按 17 编译）

## 架构与安全主线

```
Vue3(9月中旬) → Spring Boot 后端
                  ├─ 认证：BCrypt 密文 + JWT 24h + Redis 会话(login:token:{token})，登出即失效、可服务端踢人
                  ├─ 权限：RBAC 最小权限；角色存 Redis 缓存(user:roles:{id})不信任 JWT claims，
                  │        变更删缓存 → 旧 token 下次请求即时生效（第 3 周核心卖点）
                  ├─ 数据：部门/员工 CRUD + @TableLogic 离职逻辑删除 + MetaObjectHandler 自动填充
                  ├─ 敏感数据：字段级 AES-256-GCM + 动态脱敏（9/19）→ KEK/DEK + 审计日志（9/26）
                  └─ 部署：Docker Compose（mysql+redis+app），配置全环境变量化
MySQL 8.4（数据） · Redis（会话/权限缓存）
```

## 运行方式（二选一）

### A. 本地开发（IDEA / jar）

```bash
# 1. 建库建表+种子（会 DROP 重建，root 空密码；有密码改 application-local.yml）
mysql --default-character-set=utf8mb4 -uroot < sql/init.sql
# 2. 启动：IDEA 运行 HrSecurityApplication，或：
mvn package && JWT_SECRET=<至少32字节> java -jar target/hr-security-0.1.0.jar
#    密钥来源优先级：环境变量 JWT_SECRET > 本地 application-local.yml（已 gitignore）；两处都缺启动即报错
#    连接串默认 localhost:3306/6379 root 空密码，可用 MYSQL_HOST/MYSQL_PORT/MYSQL_USER/MYSQL_PASSWORD/REDIS_HOST/REDIS_PORT 覆盖
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
| 本地全量回归 | `bash test-payloads/e2e-test.sh`（20 用例）+ `bash test-payloads/redis-e2e-test.sh`（11 用例） | RBAC/CRUD + Redis 会话/角色变更/登出 |
| Docker 一键回归 | `bash test-payloads/docker-e2e-test.sh`（自动起容器并跑满 31 用例） | 部署后同一套用例全量回归 |

注意：
- 测试脚本依赖**干净种子数据**：本地重跑先重灌 `sql/init.sql` 并注册/登录 zhangsan 刷新 token（`test-payloads/` 已被 gitignore）；Docker 环境测试会残留数据，重跑需 `docker compose down -v` 后重新 up；
- 接口测试脚本里中文请求体走 UTF-8 临时文件（Git Bash 内联中文会变 GBK）；
- 业务错误（400/403/409/500）HTTP 码都是 200，需解析响应体 `code` 字段；只有 Security 的 401 才是 HTTP 401。

## 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/auth/register | 注册（BCrypt 存密码，默认 EMPLOYEE 角色） |
| POST | /api/auth/login | 登录，返回 JWT（24h）+ 写 Redis 会话 |
| POST | /api/auth/logout | 登出（删 Redis 会话，旧 token 立即失效） |
| GET | /api/auth/me | 当前用户（含最新 roles） |
| GET/POST/PUT/DELETE | /api/depts、/api/depts/{id} | 部门 CRUD（管理操作仅 ADMIN） |
| GET/POST/PUT/DELETE | /api/employees、/api/employees/{id} | 员工 CRUD：分页+keyword+部门名联查；管理操作仅 ADMIN |

内置账号：`admin / 123456`（ADMIN）。角色-权限、部门/员工表结构与种子见 `sql/init.sql`。

## 代码结构

```
src/main/java/com/hrsecurity/
├── common/       # Result/ResultCode/BusinessException/GlobalExceptionHandler/PageResult
├── config/       # SecurityConfig、RedisConfig、MybatisPlusConfig(分页)、MyMetaObjectHandler(自动填充)
├── security/     # JwtUtil(环境变量密钥+快速失败)、JwtAuthenticationFilter(验签→查会话→查角色缓存)、LoginUser
├── controller/   # Auth / Dept / Employee
├── service/      # 接口 + impl（BaseServiceImpl 基类：getOrThrow 等）
├── mapper/       # 7 个 MyBatis-Plus Mapper
├── entity/       # sys_user/dept/employee + RBAC 四表实体（@TableLogic 逻辑删除）
└── dto/          # RegisterDTO/LoginDTO/LoginResponse/LoginSession/EmployeeVO 等

sql/                 # 本地初始化脚本（DROP 重建）
docker-build/        # Dockerfile 用阿里云 settings + mysql 首次初始化 init.sql
docker-compose.yml   # mysql+redis+app 编排（数据卷+healthcheck+环境变量注入）
Dockerfile           # 多阶段构建：maven:3.9-eclipse-temurin-17 → eclipse-temurin:17-jre
```

## 已知演进点（面试深挖）

- 登录会话/角色缓存的面试必讲点：JWT 只做"身份凭证"，**会话与角色状态以 Redis 为准**——登出/角色变更在秒级生效，这就是第 3 周解决的问题（旧方案角色写死在 JWT claims 里，变更要等 token 过期）；
- 双写顺序与一致性：角色变更 = 先改库（真相源）→ 删缓存 → 下一次请求回源刷新；缓存只做 30 分钟兜底降级，不作为授权依据；
- Docker 面试点：多阶段构建（构建镜像 vs 运行镜像）、数据卷（down 不丢数据）、healthcheck + depends_on（就绪顺序）、MySQL/Redis 不发布端口（最小暴露）、配置环境变量化（镜像可移植）；
- 9/19 起的加密周将回答"密文如何检索"这类追问（见调研小结第 6 节）。
