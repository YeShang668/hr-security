# 前端工程说明（hr-ui）

> 第 5 周（2026-09-12）新增：Vue3 + Element Plus 前端，配合后端 hr-security 的认证 / RBAC / 部门 / 员工 / 用户管理接口。
> 定位：毕设演示 + 简历展示用，UI 以"能跑通、能讲清安全设计"为目标，不做花哨样式。

## 1. 技术选型

| 项 | 选择 | 说明 |
|---|---|---|
| 构建 | Vite | 开发服务器秒级启动，`/api` dev proxy 免 CORS |
| 框架 | Vue 3（`<script setup>` 组合式 API） | 项目按 Vue 3 生态写，不用 Options API |
| UI | Element Plus（全量引入 + 中文 locale） | 演示优先"少配置"，不做按需引入 |
| 状态 | Pinia | 登录态（token + 用户信息 + 角色）一个 store 管住 |
| 路由 | Vue Router（history 模式） | 路由表即菜单表，`meta.roles` 控制可访问角色 |
| 请求 | Axios（统一实例 + 拦截器） | 请求带 token；响应拆 `Result{code,message,data}`，401 统一处理 |

## 2. 目录结构

```
hr-ui/
├── index.html
├── vite.config.js          # @ 别名 + 5173 端口 + /api → localhost:8080 代理
├── src/
│   ├── main.js             # 装配 Element Plus / Pinia / Router / 图标
│   ├── App.vue             # 全局样式底座
│   ├── api/
│   │   ├── request.js      # Axios 实例、请求拦截（带 token）、响应拦截（业务 code + 401）
│   │   ├── auth.js         # 登录/注册/me/登出
│   │   ├── user.js         # 用户列表、启用禁用、角色分配、角色列表
│   │   ├── dept.js         # 部门 CRUD
│   │   └── employee.js     # 员工 CRUD
│   ├── stores/
│   │   ├── index.js        # pinia 实例（单独导出，供守卫/拦截器复用，避免循环依赖）
│   │   └── user.js         # 登录态：token、userInfo、roles、isAdmin、login/logout/fetchMe
│   ├── router/index.js     # 路由表 + 全局守卫（登录校验、角色校验、刷新恢复）
│   ├── layout/MainLayout.vue   # 侧边菜单（按角色过滤）+ 顶栏（角色标签 + 退出登录）
│   └── views/
│       ├── LoginView.vue           # 登录页（含演示账号说明）
│       ├── DashboardView.vue       # 首页：当前身份 + 演示剧本 + 安全设计要点
│       ├── EmployeeManageView.vue  # 员工管理（ADMIN 可增删改，EMPLOYEE 只读）
│       ├── DeptManageView.vue      # 部门管理（同上）
│       ├── UserManageView.vue      # 用户管理（仅 ADMIN：状态开关 + 角色分配）
│       ├── ForbiddenView.vue       # 403 页
│       └── NotFoundView.vue        # 404 页
```

## 3. 启动方式

```bash
# 前置：后端 hr-security 已启动在 8080（IDEA 运行 HrSecurityApplication 或 java -jar）
cd hr-ui
npm install            # 首次
npm run dev            # http://localhost:5173
npm run build          # 生产构建（产物 dist/，本周不做 Docker 化）
```

演示账号（种子数据）：`admin / 123456`（管理员，全部菜单）、`zhangsan / 123456`（普通员工，只读）。
注意 `zhangsan` 需先调后端注册接口创建（见后端 README / 测试脚本），且**角色变更即时生效**：

```bash
# 让 zhangsan 变成管理员（不用重新登录，旧 token 下一个请求即有 ADMIN 权限）
curl -X PUT http://localhost:8080/api/users/2/roles \
     -H "Authorization: Bearer <admin token>" -H "Content-Type: application/json" \
     -d '{"roleIds":[1,2]}'
```

## 4. 关键实现说明（面试要点）

### 4.1 为什么用 Vite dev proxy 而不是后端开 CORS
浏览器视角下前后端同源，没有预检请求，后端不用放宽 CORS；生产用 Nginx 做同样的同源转发，**开发与生产形态一致**。前端请求一律写相对路径 `/api/...`。

### 4.2 Axios 响应拦截器为什么要判两处
后端业务错误（400/403/409/500）的 **HTTP 状态码仍是 200**，错误信息在响应体 `code` 字段里；只有 Spring Security 层拦下的未登录才是真 HTTP 401。所以拦截器里"响应体 code != 200"和"HTTP 401"都要处理，只判 HTTP 状态码会漏掉全部业务错误。

### 4.3 401 统一处理（BUG5-1 修复点）
清登录态要**同时清 Pinia 内存态与 localStorage**（`clearAuth()`），再跳登录页并带上 `redirect` 参数，登录后回到原页面。只删 localStorage 会让 `isLogin` 仍为 true，守卫把用户弹回首页 —— 详见 `../docs/week5-bugfix-log.md`。

### 4.4 路由守卫与"前端隐藏 ≠ 安全"
`router.beforeEach` 三件事：未登录跳登录页并记 `redirect`；有 token 但内存无用户信息（刷新）时调 `/me` 恢复并顺带校验 token 是否已被服务端踢掉；`meta.roles` 不含当前角色则跳 403 页。

**菜单/按钮的显隐只是体验问题，权限必须由后端 `@PreAuthorize` 兜底**：EMPLOYEE 看不到用户管理菜单，但手工调 `/api/users` 一样会被后端拦成 403（本周已用接口回归用例 U3/U5/U6g 验证）。

### 4.5 登录态恢复用 `/me` 而不是只信 localStorage
刷新页面后调 `/me`：token 可能已被**登出/禁用/踢下线**（Redis 会话已删），只信本地会显示"已登录"却每个请求 401。

## 5. 演示剧本（答辩/面试现场 5 步，约 3 分钟）

| 步骤 | 操作 | 说明什么 |
|---|---|---|
| 1 | admin 登录 → 菜单 4 项；退出 → zhangsan 登录 → 菜单 3 项（无用户管理） | RBAC 菜单级权限，前端按角色渲染 |
| 2 | 用 zhangsan 身份直接访问 `/users`（或直接调接口） | 前端隐藏 ≠ 安全：守卫跳 403，后端同样 403 |
| 3 | 管理员在用户管理给 zhangsan 加 ADMIN → zhangsan **不重新登录**，刷新或点菜单即出现"新增员工"按钮 | 角色变更即时生效（服务端删了 `user:roles:{id}` 缓存） |
| 4 | 管理员禁用 zhangsan → 他在自己浏览器里点任意菜单 → 立即被踢回登录页，且无法再登录 | 禁用即踢下线（删 Redis 会话 + 角色缓存），不是只改一个标记位 |
| 5 | 退出登录→旧 token 立即 401；忘掉 token 直接访问受保护接口 → 401 | 会话在服务端，登出即失效（不是"前端删 token 就算登出"） |

## 6. 本周不做（有意留白）

- 敏感字段展示/脱敏页：等 9/19 AES-256-GCM 加密周，后端先出脱敏接口。
- 前端 Docker 化 / Nginx：10 月 HTTPS 部署周一起做。
- 按钮级权限指令、动态路由：当前用 `meta.roles` + `v-if="isAdmin"` 够用；权限编码（`employee:add` 等）已在库里，后续可演进为 `v-perm` 指令。
- 单元测试：本周用"后端 56 条接口回归 + 浏览器人工全流程"覆盖，前端测试留到后续按需补。
