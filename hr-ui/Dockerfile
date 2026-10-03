# ============================================
# hr-ui 多阶段构建（第 8 周）：Node 编译静态资源 → Nginx 托管
#
# 构建上下文是**仓库根目录**（见 docker-compose.yml 的 build.context），
# 因为要同时拿到 hr-ui/ 源码和 docker-build/nginx/hr.conf 配置。
# 构建：docker compose build web
#
# 为什么多阶段（面试点）：node_modules 有几百 MB，运行期一行都用不到；
# 最终镜像里只有 nginx + 几 MB 静态文件，构建工具链完全不进运行镜像。
# 顺带解决"前端产物里不该有源码/依赖/devtools"这条同样适用于镜像的最小化原则。
# ============================================

# ---------- 阶段 1：构建静态资源 ----------
FROM node:24-alpine AS build
WORKDIR /app
# 先只 COPY 依赖清单，利用层缓存：改业务代码不会重新 npm ci
COPY hr-ui/package.json hr-ui/package-lock.json ./
RUN npm ci
COPY hr-ui/ ./
RUN npm run build

# ---------- 阶段 2：运行（静态托管 + HTTPS 入口） ----------
FROM nginx:alpine
# 覆盖默认站点配置：80→443 跳转、TLS 参数、安全响应头、/api 反代都在这个文件里
COPY docker-build/nginx/hr.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
# 证书不打进镜像：compose 以只读卷挂载 docker-build/nginx/certs
EXPOSE 80 443
