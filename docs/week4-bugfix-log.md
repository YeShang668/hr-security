# 第 4 周 Bug 修复记录（2026-09-06，Docker Compose 部署周）

> 记录本周开发/测试中发现并修复的问题。编号规则：`BUG4-x`。每条：现象 → 定位 → 修复 → 验证。

## BUG4-1：jar 脱离本地配置文件后无法启动（Docker 化阻塞点）【高】

- **现象**：`mvn package` 打出的 jar 里没有任何配置（application.yml 在项目根目录、靠 IDEA 工作目录兜底加载）；把 jar 换目录、仅注入环境变量运行时，Spring 报 `Could not resolve placeholder 'jwt.secret'`，应用起不来——即使已正确设置 `JWT_SECRET` 环境变量。
- **定位**：① 配置放错位置：`application.yml` 一直在项目根目录而 `src/main/resources/` 不存在，jar 内没有 classpath 配置（此前全靠 IDEA 从工作目录加载，属于"能跑但不正确"）；② `JwtUtil` 的 `@Value("${jwt.secret}")` 无默认值，yml 未定义该键时启动即失败，导致 JwtUtil 里"环境变量 JWT_SECRET 优先"的代码永远走不到。
- **修复**：
  1. 新建 `src/main/resources/application.yml`，根目录原文件删除——配置随 jar 打包，运行不再依赖工作目录（已验证 jar 内 `BOOT-INF/classes/application.yml` 存在）；
  2. 数据源/Redis 全部环境变量化：`${MYSQL_HOST:localhost}`、`${MYSQL_PORT:3306}`、`${MYSQL_USER:root}`、`${MYSQL_PASSWORD:}`、`${REDIS_HOST:localhost}`、`${REDIS_PORT:6379}`，本地不带变量走默认值，容器由 compose 注入服务名；
  3. `JwtUtil` 的 `@Value` 加空默认值 `${jwt.secret:}`，密钥缺失时抛带指引的 `IllegalStateException`（不再静默用弱密钥/报晦涩错误）。
- **验证**：干净目录 + 仅 `JWT_SECRET` 环境变量启动成功（3.9s）；不设密钥启动 → 明确报错"JWT 密钥未配置：请设置环境变量 JWT_SECRET…"。

## BUG4-2：测试脚本硬编码本机路径，无法对接 Docker 容器内 MySQL/Redis【中】

- **现象**：`redis-e2e-test.sh` 直接调用本机 `mysql.exe`（-uroot 空密码）和本机 `redis-cli.exe`；容器化部署后 MySQL/Redis 不再暴露宿主端口，专项回归用例（R1/R4/R5c/R6a 等）在 Docker 环境下无法运行。
- **定位**：脚本把"环境专属"写死成常量（接口地址、客户端路径/凭据）。
- **修复**：脚本读取环境变量覆盖（默认值不变，本机行为零变化）：`API_BASE`、`MYSQL_CMD`（数据库操作完整命令前缀）、`REDIS_CLI_CMD`；`e2e-test.sh` 同样支持 `API_BASE`。新增 `test-payloads/docker-e2e-test.sh` 一键回归包装：up -d --build → 等待就绪 → 注册 zhangsan（幂等）→ 刷新双 token → 依次跑两套用例（各 31 断言，经 `docker compose exec` 操作容器内 MySQL/Redis）。
- **验证**：`bash -n` 语法通过；本地默认值下两套用例 20/20、11/11 原样通过（无回归）。容器内实际执行待 Docker Desktop 安装后由 `docker-e2e-test.sh` 验证。

## BUG4-3：初始化脚本与环境事实不一致的隐患（预防性修复）【低】

- **现象**：`sql/init.sql` 含 `DROP TABLE` 且仅注释提示"开发环境初始化用"，若误挂载为 Docker 首启脚本，与 `MYSQL_DATABASE` 环境变量建库机制重复冲突、语义危险。
- **定位**：Docker 官方 mysql 镜像约定 `/docker-entrypoint-initdb.d/` 只在空数据卷首启执行一次，且镜像已按 `MYSQL_DATABASE/MYSQL_USER` 建库授权。
- **修复**：新增 `docker-build/initdb/init.sql`：无 DROP、全 `IF NOT EXISTS` 幂等、自带 `CREATE DATABASE IF NOT EXISTS` + `USE`（兼容镜像两种执行方式）、种子数据与本地脚本一致；本地脚本保持原样（本地重建库仍用 `sql/init.sql`）。
- **验证**：本地按此文件重建库跑通全量回归（等效验证表结构与种子正确）；YAML/结构用 python 校验通过。
