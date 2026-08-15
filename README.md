# hr-security · 人事敏感数据安全管控系统

> 毕设 & 简历主项目。本周（第 1 周，2026-08-16）：项目骨架 + 登录认证。

## 技术栈

Spring Boot 3.5 · Spring Security 6 · MyBatis-Plus 3.5 · MySQL 8.4 · JWT (jjwt 0.12) · JDK 17+（本机 JDK 25 亦可）

## 运行

```bash
# 1. 建库建表（root 空密码；有密码自行改 application-local.yml）
mysql -u root < sql/init.sql

# 2. 启动（密钥在未提交的 application-local.yml 中，生产用环境变量 JWT_SECRET 覆盖）
mvn spring-boot:run
```

启动后访问 `http://localhost:8080`，接口见下方。

## 本周接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/auth/register | 注册（BCrypt 存密码） |
| POST | /api/auth/login | 登录，返回 JWT（24h 有效） |
| GET | /api/auth/me | 带 token 返回当前用户；无 token 返回 401 JSON |

测试示例：

```bash
curl -X POST localhost:8080/api/auth/register -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456","nickname":"管理员"}'

curl -X POST localhost:8080/api/auth/login -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}'

curl localhost:8080/api/auth/me -H "Authorization: Bearer <上面返回的token>"
```

## 代码结构

```
src/main/java/com/hrsecurity/
├── common/       # Result 统一返回、ResultCode、BusinessException、全局异常处理
├── config/       # SecurityConfig（Spring Security 6 过滤器链）
├── security/     # JwtUtil、JwtAuthenticationFilter、LoginUser
├── controller/   # AuthController
├── service/      # AuthService + impl
├── mapper/       # SysUserMapper（MyBatis-Plus）
├── entity/       # SysUser
└── dto/          # RegisterDTO / LoginDTO / LoginResponse / UserInfoVO
```

## 安全红线（本周已落实）

- 密码 BCrypt 加密落库，实体/日志绝不出现明文；
- JWT 密钥在 `application-local.yml`（已 gitignore），生产用环境变量 `JWT_SECRET`；
- 未登录 401 / 无权限 403 统一 JSON 返回。

## 进度

| 周次 | 任务 | 状态 |
|---|---|---|
| 第 1 周 | 项目骨架 + 登录认证 | ✅ 2026-08-16 |
| 第 2 周 | RBAC 三表 + 员工/部门 CRUD + 接口权限 | 待办 |
| 第 3 周 | Redis + MyBatis-Plus 重构 | 待办 |
| 第 4 周 | Docker Compose + 调研小结 | 待办 |
