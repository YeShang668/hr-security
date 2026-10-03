# hr-ui · 人事敏感数据安全管控系统前端

Vue3 + Vite + Element Plus + Pinia + Vue Router + Axios。配合后端 [`hr-security`](../README.md) 使用。

## 快速开始

```bash
# 1. 先启动后端（8080）：IDEA 运行 HrSecurityApplication，或
#    cd .. && java -jar target/hr-security-0.1.0.jar
# 2. 启动前端
npm install
npm run dev        # http://localhost:5173
```

演示账号：`admin / Hr@123456`（管理员）、`zhangsan / Hr@123456`（普通员工，需先注册）。
> 口令策略（第 8 周）：注册要求 8~32 位且同时含字母与数字；演示账号也一并从 `123456` 升级。
`/api` 请求由 Vite dev proxy 转发到 `http://localhost:8080`，前后端同源，无需后端开 CORS。

## 容器化（第 8 周）

`Dockerfile` 是多阶段构建（`node:24-alpine` 编译 → `nginx:alpine` 托管），
构建上下文是**仓库根目录**（要同时拿源码与 `docker-build/nginx/hr.conf`）：

```bash
cd ..                                              # 回到仓库根
bash docker-build/nginx/gen-cert.sh                # 生成自签证书（web 容器挂载）
docker compose up -d --build web                   # 或整体 up -d --build
# 访问 https://localhost（80 会 301 过来；证书自签，浏览器点"继续"）
```

生产形态下 `web` 是**唯一对外入口**（80/443）：Nginx 终止 TLS、托管 `dist/`、
把 `/api` 同源反代到 `app:8080`（后端不对宿主发布端口）。CSP/HSTS/`X-Forwarded-For` 重置等
配置与取舍见 [`../docs/deployment-https.md`](../docs/deployment-https.md)。

## 页面与权限

| 路由 | 页面 | 可访问角色 | 说明 |
|---|---|---|---|
| `/login` | 登录 | 匿名 | 登录成功存 token + 用户信息，带 `redirect` 回跳 |
| `/dashboard` | 首页 | 登录用户 | 当前身份/角色/菜单 + 演示剧本 + 安全设计要点 |
| `/employees` | 员工管理 | ADMIN 增删改，EMPLOYEE 只读 | 分页 + 关键字 + 部门筛选；删除 = 逻辑删除（离职）；敏感列脱敏 + 「查看完整信息」明文弹窗 |
| `/depts` | 部门管理 | 同上 | 部门下有员工时删除返回 409 |
| `/users` | 用户管理 | 仅 ADMIN | 账号启用/禁用（禁用即踢下线）、角色分配（即时生效） |
| `/audit-logs` | 审计日志（第 7 周） | 仅 ADMIN | 分页 + 按操作者/操作类型/结果/对象类型过滤；明细只显示"看过/改过哪些字段"，不含明文 |
| `/keys` | 密钥管理（第 7 周） | 仅 ADMIN | 密钥状态表（keyId/状态/指纹/时间，**无任何密钥材料**）+ 轮换 / 执行重加密 / 停用（有残留会拒绝） |

## 工程约定

- 请求统一走 `src/api/request.js`：请求拦截自动带 `Authorization: Bearer <token>`；响应拦截拆 `Result{code,message,data}`，`code != 200` 统一弹提示，HTTP 401 清登录态并回登录页。
- 登录态在 `src/stores/user.js`（Pinia + localStorage）；刷新页面由路由守卫调 `/api/auth/me` 恢复，顺带校验 token 是否已被服务端踢掉。
- 路由表即菜单表：`meta.title/icon` 渲染侧边栏，`meta.roles` 控制可访问角色。**前端只做显示控制，真正鉴权在后端 `@PreAuthorize`**。
- 详细设计说明与演示剧本见 [`../docs/frontend-guide.md`](../docs/frontend-guide.md)，已修 Bug 见
  [`../docs/week5-bugfix-log.md`](../docs/week5-bugfix-log.md) 与 [`../docs/week7-bugfix-log.md`](../docs/week7-bugfix-log.md)
  （第 7 周：BUG7-5 提示文案 Markdown 星号裸露、BUG7-6 表格列宽超出容器致时间列裁切——两处都是浏览器实测截图发现的）。
- 第 7 周后端设计与接口见 [`../docs/audit-design.md`](../docs/audit-design.md)（审计）、[`../docs/key-management.md`](../docs/key-management.md)（密钥轮换）。
- 第 8 周：[`../docs/security-hardening.md`](../docs/security-hardening.md)（越权矩阵/加固/CSP 的两套策略）、
  [`../docs/deployment-https.md`](../docs/deployment-https.md)（HTTPS 入口与前端容器化）、
  [`../docs/week8-bugfix-log.md`](../docs/week8-bugfix-log.md)。
  前端本轮的唯一硬约束是**不要用 `v-html`**——渲染转义依赖 Vue 插值，`security-e2e-test.sh` 的 X6 用静态断言钉住这条。
