# MVP 演示脚本（第 4 周 · Docker 一键部署演示）

> 演示口径："从零到可登录、CRUD、安全点演示，一条命令起整套环境"。
> 全程在项目根目录 `C:\Users\yeshang668\.zcode\workspace\default\hr-security` 下执行。
> 前置：Docker Desktop 已安装并启动；本机 8080 空闲（先停本地/IDEA 里的旧应用实例）。

## 第 1 幕：一键启动

```bash
docker compose up -d --build        # 首次构建镜像 + 起 mysql/redis/app，首次 MySQL 初始化约 30~60s
docker compose ps                   # 三个服务均应 Up（app 不再 Restarting）
docker compose logs -f app          # 看到 Started HrSecurityApplication 即就绪（Ctrl+C 退出日志跟随）
```

端口说明（演示解说点）：只有 8080 映射到本机；MySQL(3306)/Redis(6379) 只在容器内网，宿主机连不上——本地开发用的 MySQL/Redis 照常占用本机端口也不冲突。

## 第 2 幕：登录（拿 token）

```bash
ADMIN=$(curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" \
  --data-binary @test-payloads/login-admin.json | python -c "import json,sys;print(json.load(sys.stdin)['data']['token'])")
EMP=$(curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" \
  --data-binary @test-payloads/login-zhangsan.json | python -c "import json,sys;print(json.load(sys.stdin)['data']['token'])")
echo "$ADMIN" | head -c 40; echo "..."     # 有输出即登录成功
```

解说：密码 BCrypt 密文落库；登录即写 Redis 会话 `login:token:{token}`（TTL 24h，与 JWT 同步）。

## 第 3 幕：核心功能演示

```bash
# 1) 部门/员工 CRUD（ADMIN）
curl -s -X POST http://localhost:8080/api/depts -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" -d '{"deptName":"演示部","sort":9}'       # code 200
curl -s "http://localhost:8080/api/employees?page=1&size=10&keyword=%E5%BC%A0" -H "Authorization: Bearer $ADMIN"   # 200，分页+姓名模糊+部门名联查

# 2) 接口权限（普通员工 EMP 操作管理接口 → 403，业务错误码在响应体 code）
curl -s -X DELETE http://localhost:8080/api/depts/2 -H "Authorization: Bearer $EMP"          # 响应 code=403
```

## 第 4 幕：安全点演示（面试/答辩核心）

### 4a. 角色变更即时生效（无需等 token 过期、无需重新登录）

```bash
# zhangsan(id=2) 临时授予 ADMIN，并删除其角色缓存 → 旧 token 下一次请求就获得管理权限
docker compose exec mysql mysql -uhr -phr123456 hr_security -e "INSERT INTO sys_user_role(user_id,role_id) VALUES (2,1)"
docker compose exec redis redis-cli del "user:roles:2"
curl -s -X POST http://localhost:8080/api/employees -H "Authorization: Bearer $EMP" -H "Content-Type: application/json" \
  -d '{"empNo":"E900","name":"演示临时管理员","deptId":1}'        # 200：旧 EMP token 即时获得权限
# 收回：解除绑定 + 删缓存 → 同一 token 立即失去权限
docker compose exec mysql mysql -uhr -phr123456 hr_security -e "DELETE FROM sys_user_role WHERE user_id=2 AND role_id=1"
docker compose exec redis redis-cli del "user:roles:2"
curl -s -X POST http://localhost:8080/api/employees -H "Authorization: Bearer $EMP" -H "Content-Type: application/json" \
  -d '{"empNo":"E901","name":"应被拒绝","deptId":1}'             # code 403
```

解说：角色不写在 JWT claims 里被"永久信任"，而是每次请求查 Redis 角色缓存（30 分钟兜底、未命中回源库），删缓存即刷新——这是"最小权限动态生效"的实现细节。

### 4b. 登出即失效 / 服务端踢人

```bash
curl -s -X POST http://localhost:8080/api/auth/logout -H "Authorization: Bearer $EMP"        # 200
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/auth/me -H "Authorization: Bearer $EMP"   # 401
docker compose exec redis redis-cli exists "login:token:$EMP"    # 0：会话已删，等 JWT 过期前就已失效
# 服务端踢人：管理员可直接删他人会话 key，对方下一个请求立即 401
```

### 4c. 数据与密钥不进容器配置

```bash
docker compose exec app env | grep -E "JWT_SECRET|MYSQL_PASSWORD"    # 密钥/口令来自环境变量而非代码/配置文件
docker compose exec app ls /app                                       # 容器内只有 app.jar，无任何 yml 配置文件
```

## 第 5 幕：一键回归（自动跑 20 + 11 用例）

```bash
bash test-payloads/docker-e2e-test.sh
# 期望输出：Docker 环境回归结果：PASS=31 FAIL=0
```

## 收尾

```bash
docker compose down       # 停服务，数据保留（mysql_data/redis_data 卷）
docker compose down -v    # 彻底重置：删卷 → 下次 up 重新初始化种子数据（测试后演示用，谨慎）
```

## 常见问题

- **app 反复重启/连不上库**：MySQL 首次初始化需要时间，`docker compose ps` 看到 mysql 变 healthy 后 app 才会启动；日志 `docker compose logs mysql` 可看初始化进度。
- **想重跑全量回归**：测试数据会残留（E100 已离职、E900 等演示员工），同一数据卷不可直接重跑；`docker compose down -v && bash test-payloads/docker-e2e-test.sh` 回到干净种子再跑。
- **改了 .env 里数据库密码**：`docker-e2e-test.sh` 会读取 .env，无需手动同步。
- **宿主机端口 8080 被占**：先停旧应用实例或改 compose 里 `ports: "8081:8080"`，测试命令同步改端口。
