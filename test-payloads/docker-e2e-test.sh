#!/bin/bash
# ============================================
# hr-security Docker 环境一键回归（全量 20+11+25+40+50+52+12+30 = 240 用例）
# 第 6 周更新：新增 crypto-e2e-test.sh（AES 加密/脱敏/可检索/迁移专项 40 用例）
# 第 7 周更新：新增 audit-e2e-test.sh（审计日志 AOP/异步/查询 50 用例）
#             与 keyrotation-e2e-test.sh（KEK/DEK 轮换 30 用例），共 176 用例
# 第 8 周更新：新增 security-e2e-test.sh（越权矩阵/注入/XSS/CSRF 适用性/爆破/安全头 52 用例）
#             与 https-e2e-test.sh（Nginx+TLS 入口专项 12 用例），共 240 用例
# 前置：Docker Desktop 已启动；本机 80/443/8080 未被占用
# 用法：bash test-payloads/docker-e2e-test.sh
#
# 说明：
#  1. 先 gen-cert.sh 生成自签证书（web 容器挂载它），再带 **test override** 启动：
#       docker compose -f docker-compose.yml -f docker-compose.test.yml up -d --build
#     默认形态下 app 不对宿主发布端口（唯一入口是 web 80/443，缩小暴露面），
#     回归脚本直连端口才能跑 200+ 条用例，取舍理由见 docker-compose.test.yml 注释
#  2. 等待应用就绪 → 注册 zhangsan（幂等）→ 刷新 admin/emp 登录 token
#  3. 依次跑 e2e(20) → redis(11) → user(25) → crypto(40) → audit(50)
#     → security(52) → https(12) → keyrotation(30，必须最后跑：它会轮换密钥并停用旧密钥)
#  4. 测试会在 DB 留下 E100~E303/E900/SEC* 等测试数据，**同一数据卷不可原样重跑**；
#     重跑请重置：docker compose down -v && docker compose up -d --build（会清空数据卷，谨慎）
#  5. 容器内 KEK 由 docker-compose 的 AES_MASTER_KEY 注入（默认开发密钥）；
#     应用启动时若 sys_data_key 为空，会引导生成第一把 DEK（k1），日志有 WARN 提示
#  6. keyrotation 脚本的"KEK 缺失必须启动失败"用例通过多起一个容器进程验证（见 FAILFAST_CMD）
# ============================================
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
cd "$ROOT"

# .env 若存在则加载（docker-compose 也会自动读它，这里保持一致）
if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi

MYSQL_USER=${MYSQL_USER:-hr}
MYSQL_PASSWORD=${MYSQL_PASSWORD:-hr123456}
API=${API_BASE:-http://localhost:8080}
# 两个 -f：基础形态 + 测试专用覆盖（把 app 端口临时映射出来，见 docker-compose.test.yml）
COMPOSE="docker compose -f $ROOT/docker-compose.yml -f $ROOT/docker-compose.test.yml"

# python 解释器探测（BUG5-4，与各测试脚本一致）
PY=${PYTHON:-}
if [ -z "$PY" ]; then
  for cand in python python3 py; do
    if command -v "$cand" >/dev/null 2>&1 && "$cand" -c "pass" >/dev/null 2>&1; then PY=$cand; break; fi
  done
fi
if [ -z "$PY" ]; then
  for cand in "$LOCALAPPDATA/Python/bin/python.exe" "$LOCALAPPDATA/Programs/Python"/*/python.exe /c/Python*/python.exe; do
    [ -x "$cand" ] && PY=$cand && break
  done
fi
[ -z "$PY" ] && { echo "未找到可用的 python 解释器，请设置 PYTHON 环境变量后重试"; exit 1; }
export PYTHON="$PY"

echo "==> [1/5] 生成证书 + docker compose up -d --build（首次构建镜像需几分钟，请耐心等待）"
bash "$ROOT/docker-build/nginx/gen-cert.sh" || { echo "证书生成失败（web 容器需要它）"; exit 1; }
$COMPOSE up -d --build || { echo "构建/启动失败，请检查 Docker Desktop 是否已启动"; exit 1; }

echo "==> [2/5] 等待应用就绪（8080 登录可用，最多等 180s）"
READY=0
for i in $(seq 1 90); do
  R=$(curl -s -m 3 -X POST $API/api/auth/login -H "Content-Type: application/json" \
      --data-binary @test-payloads/login-admin.json 2>/dev/null)
  CODE=$(echo "$R" | $PY -c "import json,sys
try: print(json.loads(sys.stdin.read())['code'])
except Exception: print('')" 2>/dev/null)
  if [ "$CODE" = "200" ]; then READY=1; break; fi
  sleep 2
done
[ "$READY" = "1" ] || { echo "应用 180s 内未就绪，请查看日志：docker compose logs -f app"; exit 1; }
echo "    应用已就绪"

echo "==> [3/5] 注册 zhangsan（重复注册返回 409 属正常，忽略）"
curl -s -X POST $API/api/auth/register -H "Content-Type: application/json" \
     --data-binary @test-payloads/register.json >/dev/null

echo "==> [4/5] 刷新登录 token（admin_login.json / emp_login.json）"
curl -s -X POST $API/api/auth/login -H "Content-Type: application/json" \
     --data-binary @test-payloads/login-admin.json > test-payloads/admin_login.json
curl -s -X POST $API/api/auth/login -H "Content-Type: application/json" \
     --data-binary @test-payloads/login-zhangsan.json > test-payloads/emp_login.json

echo "==> [5/5] 全量回归：e2e(20) → redis(11) → user(25) → crypto(40) → audit(50) → security(52) → https(12) → keyrotation(30)"
OUT=$(mktemp)
TOTAL_PASS=0; TOTAL_FAIL=0; COUNT_MISMATCH=0
sum_up() { # $1=脚本名 $2=该脚本期望用例数
  LAST=$(grep -a "结果：PASS=" "$OUT" | tail -1)
  P=$(echo "$LAST" | sed -n 's/.*PASS=\([0-9]*\).*/\1/p'); F=$(echo "$LAST" | sed -n 's/.*FAIL=\([0-9]*\).*/\1/p')
  TOTAL_PASS=$((TOTAL_PASS + ${P:-0})); TOTAL_FAIL=$((TOTAL_FAIL + ${F:-0}))
  # 用例数校验（第 7 周新增，教训来自 BUG7-8）：
  # 如果某个用例因为脚本自身缺陷**既没 PASS 也没 FAIL**（例如调用了未定义的函数 → command not found），
  # 总数会悄悄少 1，而 FAIL=0 看起来一切正常，只看"是否有 FAIL"是发现不了的。
  # 所以这里逐脚本核对 PASS 数是否与期望一致。
  if [ "${P:-0}" != "$2" ]; then
    echo "!! $1 用例数异常：期望 $2，实际 PASS=${P:-0}（可能有用例既没 PASS 也没 FAIL，必须排查）"
    COUNT_MISMATCH=1
  fi
}
(cd test-payloads && bash e2e-test.sh) 2>&1 | tee "$OUT"
sum_up "e2e-test.sh" 20

export MYSQL_CMD="$COMPOSE exec -T mysql mysql -u$MYSQL_USER -p$MYSQL_PASSWORD --default-character-set=utf8mb4 hr_security -e"
export REDIS_CLI_CMD="$COMPOSE exec -T redis redis-cli"
export API_BASE=$API
(cd test-payloads && bash redis-e2e-test.sh) 2>&1 | tee "$OUT"
sum_up "redis-e2e-test.sh" 11

# 用户管理专项：会临时禁用/启用 zhangsan，脚本结束时自动恢复
(cd test-payloads && bash user-e2e-test.sh) 2>&1 | tee "$OUT"
sum_up "user-e2e-test.sh" 25

# 加密专项：AES 字段加密 / 动态脱敏 / 哈希可检索 / 幂等刷数，会临时清空 E001~E003 再重新迁移（幂等）
(cd test-payloads && bash crypto-e2e-test.sh) 2>&1 | tee "$OUT"
sum_up "crypto-e2e-test.sh" 40

# 审计专项：AOP 埋点 / 异步落库 / 查询过滤 / 权限边界，会临时禁用改角色 zhangsan 并自动恢复
(cd test-payloads && bash audit-e2e-test.sh) 2>&1 | tee "$OUT"
sum_up "audit-e2e-test.sh" 50

# 安全加固专项（第 8 周）：越权矩阵 / 注入 / XSS / CSRF 适用性 / 登录爆破防护 / 安全响应头
# 注意：它会把 Redis 里 login:fail:* 计数清空（脚本结尾自带清理），不会锁定真实账号
(cd test-payloads && bash security-e2e-test.sh) 2>&1 | tee "$OUT"
sum_up "security-e2e-test.sh" 52

# HTTPS 入口专项（第 8 周）：跳转/TLS 版本/证书 SAN/HSTS/安全头/SPA 回退/反代可用性/XFF 重置
# 这一套走 443（自签证书，用例内部显式 -k，理由见脚本头与 docker-compose.test.yml）
(cd test-payloads && bash https-e2e-test.sh) 2>&1 | tee "$OUT"
sum_up "https-e2e-test.sh" 12

# 密钥轮换专项（必须最后跑）：轮换到 k2、分批重加密、停用 k1；
# "KEK 缺失启动失败"用例借容器再起一个进程验证（覆盖 AES_MASTER_KEY 为空 → 必须启动失败）
export FAILFAST_CMD="cd $ROOT && $COMPOSE run --rm -T -e AES_MASTER_KEY= app"
(cd test-payloads && bash keyrotation-e2e-test.sh) 2>&1 | tee "$OUT"
sum_up "keyrotation-e2e-test.sh" 30
rm -f "$OUT"

echo
echo "=========================================="
echo "Docker 环境回归结果：PASS=$TOTAL_PASS FAIL=$TOTAL_FAIL"
if [ "$TOTAL_FAIL" -gt 0 ] || [ "$COUNT_MISMATCH" != "0" ] || [ "$TOTAL_PASS" -ne 240 ]; then
  echo "存在失败用例！如需干净环境重跑：docker compose down -v 后重新执行本脚本（会清空数据库，谨慎）"
  exit 1
fi
echo "全部通过 ✅  演示状态查看：docker compose ps；HTTPS 入口：https://localhost"
