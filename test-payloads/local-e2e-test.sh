#!/bin/bash
# ============================================
# hr-security 本地环境一键回归（全量 176 用例：20+11+25+40+50+30）
#
# 前置条件（脚本会先自检，不满足会明确提示）：
#   1. 本机 MySQL 与 Redis 已启动；
#   2. 已重灌种子数据：mysql --default-character-set=utf8mb4 -uroot < sql/init.sql
#      —— 重灌后**必须重启应用**（进程内存里仍持有旧 DEK，重启才会重新引导 sys_data_key）；
#   3. 应用已启动（IDEA 或 java -jar target/hr-security-0.1.0.jar）。
#
# 用法：bash test-payloads/local-e2e-test.sh
# 说明：脚本会自动注册 zhangsan（幂等）并刷新 admin/emp 登录 token；
#       keyrotation 会轮换密钥，所以放在最后跑；重跑请重灌 init.sql + 重启应用。
# 与 docker-e2e-test.sh 的关系：用例完全相同，只是把 MySQL/Redis 命令换成直连本机。
# ============================================
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
API=${API_BASE:-http://localhost:8080}
MYSQL=${MYSQL_CMD:-/e/devlop/mysql-8.4.9-winx64/bin/mysql.exe --default-character-set=utf8mb4 -uroot hr_security -e}
REDIS_CLI=${REDIS_CLI_CMD:-/e/devlop/redis-windows-8.10.1/Redis-8.10.1-Windows-x64-msys2/redis-cli.exe}
export MYSQL_CMD="$MYSQL" REDIS_CLI_CMD="$REDIS_CLI" API_BASE="$API"

# python 解释器探测（BUG5-4，与各脚本一致）
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

echo "==> [1/3] 环境自检"
CODE=$(curl -s -m 5 -o /dev/null -w "%{http_code}" -X POST $API/api/auth/login \
       -H "Content-Type: application/json" --data-binary @login-admin.json 2>/dev/null || true)
if [ "$CODE" != "200" ]; then
  echo "应用未就绪（登录返回 $CODE）。请确认：MySQL/Redis 已启动、已重灌 sql/init.sql、应用已启动。"
  exit 1
fi
$MYSQL "SELECT 1" >/dev/null 2>&1 || { echo "MySQL 直连失败，请检查 MYSQL_CMD（默认本机 mysql 客户端路径）"; exit 1; }
[ -n "$($REDIS_CLI ping 2>/dev/null | grep -i pong)" ] || { echo "Redis 不可用，请检查 REDIS_CLI_CMD"; exit 1; }
echo "    应用/MySQL/Redis 均可用"

echo "==> [2/3] 注册 zhangsan（已存在返回 409 属正常）并刷新登录 token"
curl -s -X POST $API/api/auth/register -H "Content-Type: application/json" --data-binary @register.json >/dev/null
curl -s -X POST $API/api/auth/login -H "Content-Type: application/json" --data-binary @login-admin.json > admin_login.json
curl -s -X POST $API/api/auth/login -H "Content-Type: application/json" --data-binary @login-zhangsan.json > emp_login.json

echo "==> [3/3] 全量回归：e2e(20) → redis(11) → user(25) → crypto(40) → audit(50) → keyrotation(30)"
OUT=$(mktemp)
TOTAL_PASS=0; TOTAL_FAIL=0; COUNT_MISMATCH=0
sum_up() { # $1=脚本名 $2=该脚本期望用例数
  LAST=$(grep -a "结果：PASS=" "$OUT" | tail -1)
  P=$(echo "$LAST" | sed -n 's/.*PASS=\([0-9]*\).*/\1/p'); F=$(echo "$LAST" | sed -n 's/.*FAIL=\([0-9]*\).*/\1/p')
  TOTAL_PASS=$((TOTAL_PASS + ${P:-0})); TOTAL_FAIL=$((TOTAL_FAIL + ${F:-0}))
  # 用例数校验：某个用例若因脚本自身缺陷既没 PASS 也没 FAIL（如调用未定义函数），
  # 总数会悄悄少 1 而 FAIL 仍为 0 —— 只看 FAIL 是发现不了的（见 docs/week7-bugfix-log.md BUG7-8）
  if [ "${P:-0}" != "$2" ]; then
    echo "!! $1 用例数异常：期望 $2，实际 PASS=${P:-0}（可能有用例既没 PASS 也没 FAIL，必须排查）"
    COUNT_MISMATCH=1
  fi
}
for pair in "e2e-test.sh:20" "redis-e2e-test.sh:11" "user-e2e-test.sh:25" \
            "crypto-e2e-test.sh:40" "audit-e2e-test.sh:50" "keyrotation-e2e-test.sh:30"; do
  SCRIPT="${pair%%:*}"; EXPECT="${pair##*:}"
  (bash "$SCRIPT") 2>&1 | tee "$OUT"
  sum_up "$SCRIPT" "$EXPECT"
done
rm -f "$OUT"

echo
echo "=========================================="
echo "本地环境回归结果：PASS=$TOTAL_PASS FAIL=$TOTAL_FAIL"
if [ "$TOTAL_FAIL" -gt 0 ] || [ "$COUNT_MISMATCH" != "0" ] || [ "$TOTAL_PASS" -ne 176 ]; then
  echo "存在失败用例！干净环境重跑：重灌 sql/init.sql → 重启应用 → 再执行本脚本"
  exit 1
fi
echo "全部通过 ✅ （容器环境请用 docker-e2e-test.sh 或 GitHub Actions 的 docker-regression）"
