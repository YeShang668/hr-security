# HTTPS 部署与前端容器化（第 8 周）

> 论文用途：可直接改写为**第 7 章 部署与运维加固**的底稿（与 `security-hardening.md` 合成一章）。
> 可执行证据：`test-payloads/https-e2e-test.sh`（12 条用例，随容器回归一起跑：CI 240 用例）。

## 1. 部署形态（改了什么）

第 4 周起就是"三容器 Compose"，第 8 周把**入口收敛**了一层：

```
                      宿主机只暴露 80 / 443
                              │
        ┌─────────────────────▼─────────────────────┐
        │            web  (nginx:alpine)             │
        │  80 → 301 → 443    TLS 终止 / HSTS / CSP   │
        │  静态托管：Vue3 构建产物 (dist/)           │
        │  /api/  ──反代──►  http://app:8080         │
        └─────────────────────┬─────────────────────┘
                              │  hr-net（应用不对宿主发布端口）
        ┌─────────────────────▼─────────────────────┐
        │          app  (Spring Boot 3 / JRE17)      │
        └───────────┬───────────────────┬───────────┘
                    │                   │
              ┌─────▼─────┐       ┌─────▼─────┐
              │  mysql:8.4│       │ redis:7   │      （都不发布端口）
              └───────────┘       └───────────┘
```

三处变化，每一处都有明确理由：

1. **新增 `web` 服务**（`hr-ui/Dockerfile` 多阶段 + `docker-build/nginx/hr.conf`）：
   Node 构建静态资源 → Nginx 托管。运行镜像里没有 node_modules、没有源码、没有构建工具链。
2. **`app` 不再对宿主发布端口**：暴露面从"前端 80 + 后端 8080 两个入口"收敛成"80/443 一个入口"。
   后端只在内网被别人用服务名访问，宿主机上扫不到 8080。
3. **证书以只读卷挂载**（`./docker-build/nginx/certs:/etc/nginx/certs:ro`）：
   私钥不进镜像层（`.dockerignore` 也排除了该目录），换证书不用重新构建镜像。

## 2. 关键配置与理由

### 2.1 TLS 参数（`docker-build/nginx/hr.conf`）

| 配置 | 值 | 理由 |
|---|---|---|
| `ssl_protocols` | `TLSv1.2 TLSv1.3` | TLS 1.0/1.1 已被 RFC 8996 弃用（BEAST/POODLE 一类历史问题） |
| `ssl_ciphers` | 仅 ECDHE + AEAD（GCM/CHACHA20） | 只要前向保密与 AEAD，去掉 CBC 与静态 RSA 套件 |
| `ssl_prefer_server_ciphers` | `off` | 交给客户端挑（TLS1.3 的套件本就不受该开关影响） |
| `ssl_session_tickets` | `off` | 单机演示场景下只增加密钥管理复杂度 |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains`（**不加 preload**） | HSTS 一旦下发就不可轻易撤销，preload 更是不可逆，演示环境不加 |

### 2.2 三个 HTTPS 常见坑，以及本项目怎么处理

1. **反代后应用以为自己是 http**（`X-Forwarded-Proto` 丢失）
   → `application.yml` 设 `server.forward-headers-strategy: native`（Tomcat RemoteIpValve 还原 scheme），
   于是 `request.isSecure()` 为真、HSTS 才会下发（用例 S5/S6 正反验证）。
   *为什么不用 `framework`（ForwardedHeaderFilter）*：它会把 `X-Forwarded-*` 头**从请求里摘掉**避免重复处理，
   而本项目的 `IpUtils` 要直接读该头解析代理链；`native` 只改写 remoteAddr/scheme，不动请求头，两者可共存。
2. **客户端 IP 变成网关地址**
   → Nginx `proxy_set_header X-Forwarded-For $remote_addr`：**重置而非追加**。
   应用侧 `IpUtils` 取最左地址，于是拿到的就是"可信的最外层代理看到的客户端"。
   如果写成 `$proxy_add_x_forwarded_for`（追加），客户端自带的 `X-Forwarded-For: 1.2.3.4` 就会留在最左边，
   审计里的来源 IP 与登录限流的 IP 维度**双双可伪造**。`https-e2e-test.sh` 的 T12 就是断言它失效。
3. **混合内容**（页面 https、接口 http 被浏览器拦截）
   → `/api` 同源反代，浏览器视角只有一个域；顺带也不需要后端开 CORS，
   与开发期 Vite dev proxy 的形态一致（同一套前端代码在 dev 与 prod 都是相对路径 `/api`）。

### 2.3 一个容易踩的 nginx 细节

`add_header` 在 `location` 里一旦出现，**该 location 就不再继承 server 级的其它 add_header**。
所以本项目的 `/assets/` 目录**没有**单独加 `Cache-Control`（那会让安全头在这个 location 里消失）。
静态资源缓存只是优化项，本周优先保证"安全头在每个响应上都在场"——这是一个刻意取舍，
不是忘了配缓存（要两者兼得就把全部头在两个位置都写一遍，或用 `include` 抽公共段）。

### 2.4 前端 CSP 与接口 CSP 不同（刻意）

| 对象 | CSP | 原因 |
|---|---|---|
| 接口（Spring Security 下发） | `default-src 'none'` | 只产出 JSON，不需要加载任何资源 |
| 页面（Nginx 下发） | `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data: blob:; object-src 'none'; frame-ancestors 'none'; base-uri 'self'` | SPA 需要自身脚本与样式；`style` 的 `'unsafe-inline'` 是 Element Plus 运行时注入样式所必需 |

`script-src 'self'` 的安全前提是"构建产物里没有内联脚本"——这条已经核对过：
`hr-ui/dist/index.html` 只含外部 `<script type="module" src="/assets/...">`，无内联脚本。

## 3. 证书：本地自签 → 生产 Let's Encrypt

```bash
# 生成（幂等，已存在则跳过）
bash docker-build/nginx/gen-cert.sh
# 产物：docker-build/nginx/certs/{server.crt,server.key,ca.crt}（目录已 gitignore，私钥绝不入库）
```

自签证书的两个要点：

- **SAN 必须写** `DNS:localhost,IP:127.0.0.1`：现代客户端（curl 7.65+/Chrome 58+）**不再看 CN**，只看 SAN。
- 生产环境替换方式：`certbot --nginx -d your.domain`（自动签发 + 续期 + 装到 nginx），
  或把商业证书放到 `certs/` 同名文件即可（配置无需改动，因为走的是挂载路径）。

## 4. 验证方式

```bash
# 容器形态（默认 docker-compose.yml：唯一入口 web）
bash docker-build/nginx/gen-cert.sh
docker compose up -d --build
curl -kI https://localhost                      # 200 + HSTS + CSP
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/     # 301
curl -k  https://localhost/api/depts -H "Authorization: Bearer $TOKEN"   # 反代可用
```

自动化用例（`test-payloads/https-e2e-test.sh`，12 条）：

| 用例 | 断言 |
|---|---|
| T1 / T2 | 80 → 301，Location 以 `https://` 开头 |
| T3 | 证书 SAN 含 `DNS:localhost`（不是只看 CN） |
| T4 / T5 | TLS1.2 可握手（拿到服务端证书）；TLS1.1 拿不到证书 |
| T6 / T7 | `/` 返回 SPA 首页（text/html）；前端路由 `/audit-logs` 刷新不 404（try_files 回退） |
| T8 / T9 / T10 | HSTS / X-Frame-Options=DENY / CSP 由入口层下发 |
| T11 | `https` 下 `/api` 反代可用（登录 200，前端无需 CORS） |
| **T12** | **伪造 `X-Forwarded-For: 203.0.113.9` 被入口层重置**，审计记录的 IP ≠ 伪造值 |

## 5. 回归路径的取舍（为什么有 `docker-compose.test.yml`）

这是本周最需要解释清楚的一个决策，因为它看起来"自相矛盾"：

- **默认形态**（`docker-compose.yml`）不暴露 app 端口，唯一入口 443 —— 这是**部署形态**。
- 但回归脚本有 228 条用例需要直连接口，而 https 入口用的是**自签证书**：
  - Linux（CI runner）：把自签证书装进系统信任库（`update-ca-certificates`）后可正常校验；
  - Windows（Git Bash 的 curl 是 **Schannel** 后端）：**不读 `CURL_CA_BUNDLE`，也不认 `--cacert`**，
    只认 Windows 证书库。要么把开发证书导入本机信任库（`certutil -addstore -user Root`），
    要么加 `-k` 跳过校验——而 `-k` 会让 228 条用例**全部失去证书校验能力**（假绿风险）。

所以最终方案是：**回归用 `docker-compose.test.yml` 临时把 8080 映射出来走 http**
（228 条用例保持原有的严格性），**TLS 入口单独一套 12 条用例**（`https-e2e-test.sh`，
内部显式 `-k`，只验证入口形态与代理语义，证书本身用 `openssl` 直接检查 SAN）。

> 结论：`-k` 只出现在"验证入口形态"的 12 条里，没有污染 228 条功能用例。
> 若要把全量回归也搬上 https，正确做法是"把自签 CA 装进运行环境的信任库"，
> 而不是给所有用例加 `-k`（README 的容器回归小节写了具体命令）。

## 6. 生产化清单（本周没做，答辩要能说出来）

1. 证书换 Let's Encrypt/商业 CA + 自动续期；HSTS 加 `preload`（确认全站 https 后）。
2. 密钥（JWT_SECRET / AES_MASTER_KEY=KEK）从环境变量改为 **KMS/密钥管理服务**托管。
3. 加 `docker compose` 的 `healthcheck` 到 app（现在是 mysql/redis 有、app 没有），
   web 依赖 app 健康状态。
4. 静态资源加长缓存 + `nginx` 的 gzip/brotli 压缩（注意 2.3 的 add_header 继承坑）。
5. 入口层加限流（`limit_req`）与连接数限制，与应用内的登录限流形成两层。
6. 用非 root 用户跑容器、只读根文件系统、`cap_drop: ALL`（最小权限）。
7. 镜像扫描（trivy）与依赖扫描（dependency-check）进 CI。
8. 备份与恢复演练（MySQL 数据卷 + `sys_data_key` 表必须一起备份，否则密文无法解密）。

## 7. 面试话术（30 秒版）

> "我把部署形态从'前端 80 + 后端 8080 两个入口'收敛成'80/443 一个入口'：
> 新增 Nginx 容器做 TLS 终止 + 静态托管 + `/api` 同源反代，后端不再对宿主发布端口。
> 配 TLS 时把三个经典坑都处理了：用 `forward-headers-strategy` 让应用知道外面是 https、
> 让 Nginx **重置**而不是追加 `X-Forwarded-For`（否则审计 IP 和登录限流的 IP 维度都能被伪造，
> 这条我写了用例断言伪造失效）、靠同源反代规避混合内容。
> 证书以只读卷挂载不进镜像，本地自签、生产换 Let's Encrypt。
> 另外有个取舍我特意留在文档里：回归为什么没全走 https —— 因为 Windows 的 curl 是 Schannel 后端
> 不认 `CURL_CA_BUNDLE`，给 228 条用例加 `-k` 等于放弃证书校验，所以把 TLS 单独拆了 12 条专项用例。"
