# 第 5 周 Bug 修复记录（2026-09-12）

> 范围：前端工程（hr-ui）+ 后端用户管理接口（第 5 周新增）
> 记录口径：能复现 → 定位根因 → 修复 → 回归验证，四步都写清楚，面试问到"你踩过什么坑"可直接用。
> 本周回归结果：`e2e 20/20` + `redis-e2e 11/11` + `user-e2e 25/25` = **56/56 通过**（本地 jar 实测）。

---

## BUG5-1（重要）401 拦截器只清了 localStorage，没清 Pinia 状态，用户被"弹回首页"

**现象**
用 zhangsan 的浏览器登录后，管理员在后台把这个账号**禁用**（后端已删 Redis 会话）。此时 zhangsan 再点任何菜单，页面不是跳到登录页，而是**跳回首页 /dashboard 并继续显示登录态**；后续每个请求都失败并弹"登录状态已失效"，但用户始终回不到登录页。

**根因**
`src/api/request.js` 的 401 处理只做了 `localStorage.removeItem(...)`，没有清 Pinia。而 `userStore.token` 是内存状态，只在应用启动时从 localStorage 读一次，所以：

1. 401 → 删 localStorage（内存里的 `token` 还在）→ `userStore.isLogin` 仍为 `true`；
2. `router.replace('/login')` → 路由守卫看到 `isLogin === true` → 判定"已登录不该看登录页"→ `return { path: '/' }`；
3. `/` 重定向到 `/dashboard` → 用户被弹回首页，卡在"假登录"状态。

**修复**（`src/api/request.js`）
401 处理改为动态 import 出 Pinia 实例并调用 `userStore.clearAuth()`（该 action 同时清内存状态与 localStorage），再跳登录页；`router` 也一并动态 import，继续规避 `request → router → store → api → request` 的循环依赖。

**验证**
浏览器实测：zhangsan 在线 → 管理员禁用 → 他点菜单 → 立即跳 `/login?redirect=/employees`；
另测：Redis 会话被清后刷新页面 → 同样回到登录页。修复前是弹回 `/dashboard`。

**面试话术**：前端登录态有"内存 store + localStorage"两份，清理时必须两份一起清；只清一份会出现"看起来还登录着、实际每个请求都 401"的最糟状态——守卫逻辑反而把用户挡在登录页外。

---

## BUG5-2 角色分配弹窗把 Markdown 粗体标记原样显示

**现象**：用户管理 → 分配角色弹窗顶部提示显示成 `改成"管理员"后，该用户**不需要重新登录**，...`，星号直接暴露给用户。

**根因**：文案里写了 Markdown 的 `**加粗**`，但 Element Plus 的 `el-alert` 标题不做 Markdown 渲染，原样输出。

**修复**：去掉 `**`（`src/views/UserManageView.vue`），需要强调就靠措辞而不是标记。

**教训**：往 UI 文案里写 Markdown 标记前，先确认组件支不支持 Markdown。

---

## BUG5-3（后端）部门/角色接口返回 `2026-09-12T19:06:44`，与其他页面格式不一致

**现象**：部门管理列表、角色列表的"创建时间"显示成 ISO 串（带 `T`）；而员工列表、用户列表显示 `2026-09-12 19:06:44`。同一个系统两种时间写法。

**根因**：`application.yml` 里的 `spring.jackson.date-format` **只对 `java.util.Date` 生效**，对 `LocalDateTime`/`LocalDate` 无效。员工/用户列表走 VO，VO 字段上有 `@JsonFormat(pattern=...)` 所以正常；部门/角色接口直接返回实体（`SysDept`/`SysRole`），字段上没有注解，就走了 Jackson 默认的 ISO-8601 输出。

**修复**：新增 `config/JacksonConfig.java`，用 `Jackson2ObjectMapperBuilderCustomizer` 给 `LocalDateTime`/`LocalDate` 注册全局序列化器（`yyyy-MM-dd HH:mm:ss` / `yyyy-MM-dd`）与反序列化器；VO 上已有的 `@JsonFormat` 优先级更高且取值一致，不会冲突。

**验证**：`/api/depts`、`/api/roles` 返回值变为 `2026-09-12 19:56:14`；56 条回归用例全绿（未影响其他接口）。

**面试话术**：`spring.jackson.date-format` 管不到 JSR-310 的 `LocalDateTime`，这是 Spring Boot 项目里很常见的"时间格式一半对一半错"的坑；正确做法是全局注册 `JavaTimeModule` 序列化器，而不是在每个 VO 上贴 `@JsonFormat`。

---

## BUG5-4（测试脚本）脚本依赖 `python` 命令，本机被 Windows 应用别名占位，导致 7 条用例误报失败

**现象**：重跑 `e2e-test.sh` 出现 `PASS=13 FAIL=7`，失败的清一色是 PUT/DELETE 用例（B3/B4/C4/C7），控制台夹杂 `Python was not found; ...` 提示。

**根因**：脚本里用 `python -c ...` 解析 JSON 与提取 `DEPT_ID`/`EMP_ID`。本机 Git Bash 下 `python` 解析到 **Windows 应用商店别名占位程序**——`command -v python` 能找到，但执行只打印"Python was not found"并失败。于是 `DEPT_ID` 为空，请求打到 `/api/depts/`（无 id 路径）导致 500，看起来像后端 bug，实际是测试脚手架问题。

**修复**：三个测试脚本（`e2e-test.sh`、`redis-e2e-test.sh`、`user-e2e-test.sh`）与 `docker-e2e-test.sh` 统一加"解释器探测"：

1. 支持 `PYTHON` 环境变量显式指定；
2. 依次探测 `python` / `python3` / `py`，**必须真正执行成功**才算命中（用 `-c "pass"` 试跑，把别名占位程序挡掉）；
3. 都失败则退到常见安装路径（`$LOCALAPPDATA/Python/bin/python.exe` 等）；
4. 仍然找不到就明确报错退出，而不是带着空变量继续跑出假失败。

脚本内所有 `python` 调用改为 `$PY`。

**验证**：从干净库重跑三套脚本 = 56/56 全过。

**教训（重要）**：**测试脚手架的故障会伪装成业务缺陷**。看到"一排用例同一模式失败"，先怀疑公共前置（变量为空/解析失败），别急着改业务代码；脚本里的隐式环境依赖（`python`、`curl`、路径分隔符）要显式探测并快速失败。

---

## BUG5-5（UI）用户/部门管理表格列宽之和超出容器，"创建时间"被横向滚动裁切

**现象**：1280 宽窗口下，用户管理列表最右侧"创建时间"显示成 `2026-09-12 19:56:1`，后面被固定列"操作"盖住。

**根因**：列宽之和（70+140+130+180+150+180+180=1030）超过内容区可用宽度，`el-table` 出现横向滚动，而"操作"是 `fixed="right"` 固定列，覆盖在滚动内容之上。

**修复**：收窄列宽（ID 60 / 用户名 120 / 姓名 110 / 角色 min-width 150 / 状态 130 / 创建时间 190 + `show-overflow-tooltip`），部门页同样处理，使表格在 1280 宽下无需横向滚动。

---

## 附：本周测试方法上的新收获（非 Bug，但值得记）

1. **自动化点 Element Plus 的复选框/开关要点视觉容器**：`el-checkbox`/`el-switch` 内部的原生 `input` 被视觉隐藏（`opacity:0`），Playwright 按 `role=checkbox` / `role=switch` 点击会因 actionability（hidden）超时。正确做法是点可见的 `.el-checkbox` / `.el-switch` 根元素（用户可以点的地方）。
2. **下拉框（`el-select`）的弹层在"打开状态"才可见**：必须"点开 + 选选项"在同一个操作批次里完成，分两次调用时弹层已关闭，选项 `isVisible()` 全部为 false。
3. **前端隐藏 ≠ 安全**：本周在浏览器里验证了"EMPLOYEE 看不到用户管理菜单、直接输 URL 被守卫拦到 403"，同时用接口回归验证"同样的请求后端返回 403"——这两条一起讲才是完整答案。
