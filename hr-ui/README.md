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

演示账号：`admin / 123456`（管理员）、`zhangsan / 123456`（普通员工，需先注册）。
`/api` 请求由 Vite dev proxy 转发到 `http://localhost:8080`，前后端同源，无需后端开 CORS。

## 页面与权限

| 路由 | 页面 | 可访问角色 | 说明 |
|---|---|---|---|
| `/login` | 登录 | 匿名 | 登录成功存 token + 用户信息，带 `redirect` 回跳 |
| `/dashboard` | 首页 | 登录用户 | 当前身份/角色/菜单 + 演示剧本 + 安全设计要点 |
| `/employees` | 员工管理 | ADMIN 增删改，EMPLOYEE 只读 | 分页 + 关键字 + 部门筛选；删除 = 逻辑删除（离职） |
| `/depts` | 部门管理 | 同上 | 部门下有员工时删除返回 409 |
| `/users` | 用户管理 | 仅 ADMIN | 账号启用/禁用（禁用即踢下线）、角色分配（即时生效） |

## 工程约定

- 请求统一走 `src/api/request.js`：请求拦截自动带 `Authorization: Bearer <token>`；响应拦截拆 `Result{code,message,data}`，`code != 200` 统一弹提示，HTTP 401 清登录态并回登录页。
- 登录态在 `src/stores/user.js`（Pinia + localStorage）；刷新页面由路由守卫调 `/api/auth/me` 恢复，顺带校验 token 是否已被服务端踢掉。
- 路由表即菜单表：`meta.title/icon` 渲染侧边栏，`meta.roles` 控制可访问角色。**前端只做显示控制，真正鉴权在后端 `@PreAuthorize`**。
- 详细设计说明与演示剧本见 [`../docs/frontend-guide.md`](../docs/frontend-guide.md)，已修 Bug 见 [`../docs/week5-bugfix-log.md`](../docs/week5-bugfix-log.md)。
