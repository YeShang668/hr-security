#!/bin/bash
# ============================================
# hr-security Docker 环境一键回归（全量 20 + 11 + 25 + 40 = 96 用例）
# 第 6 周更新：新增 crypto-e2e-test.sh（AES 加密/脱敏/可检索/迁移专项 40 用例）
# 前置：Docker Desktop 已启动；本机 8080 未被占用（请先停掉旧的本地 app 实例）
# 用法：bash test-payloads/docker-e2e-test.sh
#
# 说明：
#  1. docker compose up -d --build（首次自动建库建表+种子数据，MySQL 初始化需等 30~60s）
#  2. 等待应用就绪 → 注册 zhangsan（幂等）→ 刷新 admin/emp 登录 token
#  3. 跑 e2e-test.sh（20）+ redis-e2e-test.sh（11）+ user-e2e-test.sh（25）
#     + crypto-e2e-test.sh（40，经 docker compose exec 操作容器内 MySQL/Redis）
#  4. 测试会在 DB 留下 E100~E303/E900 等测试数据，**同一数据卷不可原样重跑**；
#     重跑请重置：docker compose down -v && docker compose up -d --build（会清空数据卷，谨慎）
#  5. 容器内 AES 主密钥由 docker-compose 的 AES_MASTER_KEY 注入（默认开发密钥）
# ============================================
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
cd "$ROOT"

# .env 若存在则加载（docker-compose 也会自动读它，这里保持一致）
if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi

MYSQL_USER=${MYSQL_USER:-hr}
MYSQL_PASSWORD=${MYSQL_PASSWORD:-hr123456}
API=${API_BASE:-http://localhost:8080}
COMPOSE="docker compose -f $ROOT/docker-compose.yml"

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

echo "==> [1/5] docker compose up -d --build（首次构建镜像需几分钟，请耐心等待）"
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

echo "==> [5/5] 全量回归：e2e（20）→ redis-e2e（11）→ user-e2e（25）→ crypto-e2e（40）"
OUT=$(mktemp)
TOTAL_PASS=0; TOTAL_FAIL=0
sum_up() { # 从 tee 出来的结果里累计 PASS/FAIL
  LAST=$(grep "结果：PASS=" "$OUT" | tail -1)
  P=$(echo "$LAST" | sed -n 's/.*PASS=\([0-9]*\).*/\1/p'); F=$(echo "$LAST" | sed -n 's/.*FAIL=\([0-9]*\).*/\1/p')
  TOTAL_PASS=$((TOTAL_PASS + ${P:-0})); TOTAL_FAIL=$((TOTAL_FAIL + ${F:-0}))
}
(cd test-payloads && bash e2e-test.sh) 2>&1 | tee "$OUT"
sum_up

export MYSQL_CMD="$COMPOSE exec -T mysql mysql -u$MYSQL_USER -p$MYSQL_PASSWORD --default-character-set=utf8mb4 hr_security -e"
export REDIS_CLI_CMD="$COMPOSE exec -T redis redis-cli"
export API_BASE=$API
(cd test-payloads && bash redis-e2e-test.sh) 2>&1 | tee "$OUT"
sum_up

# 用户管理专项：会临时禁用/启用 zhangsan，脚本结束时自动恢复
(cd test-payloads && bash user-e2e-test.sh) 2>&1 | tee "$OUT"
sum_up

# 加密专项：AES 字段加密 / 动态脱敏 / 哈希可检索 / 幂等刷数，会临时清空 E001~E003 再重新迁移（幂等）
(cd test-payloads && bash crypto-e2e-test.sh) 2>&1 | tee "$OUT"
sum_up
rm -f "$OUT"

echo
echo "=========================================="
echo "Docker 环境回归结果：PASS=$TOTAL_PASS FAIL=$TOTAL_FAIL"
if [ "$TOTAL_FAIL" -gt 0 ] || [ "$TOTAL_PASS" -ne 96 ]; then
  echo "存在失败用例！如需干净环境重跑：docker compose down -v 后重新执行本脚本（会清空数据库，谨慎）"
  exit 1
fi
echo "全部通过 ✅  演示状态查看：docker compose ps"
