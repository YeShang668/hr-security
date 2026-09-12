#!/bin/bash
# hr-security 第 3 周 Redis 专项端到端测试（登录会话 + 权限缓存 + 角色变更即时生效 + 登出即失效）
# 前置：先跑 e2e-test.sh 同目录的 admin_login.json/emp_login.json（本脚本直接复用 token）
# 环境变量覆盖（供 Docker 环境回归用，见 docker-e2e-test.sh）：
#   API_BASE=接口地址        MYSQL_CMD=操作数据库的完整命令前缀   REDIS_CLI_CMD=redis 客户端命令前缀
# 用法：bash redis-e2e-test.sh
cd "$(dirname "$0")"

# python 解释器探测（同 e2e-test.sh，见 BUG5-4）
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

ADMIN_TOKEN=$($PY -c "import json;print(json.load(open('admin_login.json'))['data']['token'])")
EMP_TOKEN=$($PY -c "import json;print(json.load(open('emp_login.json'))['data']['token'])")
API=${API_BASE:-http://localhost:8080}
MYSQL=${MYSQL_CMD:-/e/devlop/mysql-8.4.9-winx64/bin/mysql.exe --default-character-set=utf8mb4 -uroot hr_security -e}
REDIS_CLI=${REDIS_CLI_CMD:-/e/devlop/redis-windows-8.10.1/Redis-8.10.1-Windows-x64-msys2/redis-cli.exe}
PASS=0; FAIL=0
check() { if [ "$3" = "$2" ]; then echo "PASS | $1"; PASS=$((PASS+1));
  else echo "FAIL | $1 (期望 code=$2 实际 code=$3)"; FAIL=$((FAIL+1)); fi }
json_code() { $PY -c "import json,sys;print(json.loads(sys.argv[1])['code'])" "$1"; }

echo "===== R. Redis 会话与权限缓存 ====="
TTL=$($REDIS_CLI ttl "login:token:$ADMIN_TOKEN")
if [ "$TTL" -gt 85000 ] && [ "$TTL" -le 86400 ]; then echo "PASS | R1 会话TTL≈24h (实际 ${TTL}s)"; PASS=$((PASS+1));
  else echo "FAIL | R1 会话TTL=$TTL 期望≈86400"; FAIL=$((FAIL+1)); fi
CODE=$(curl -s -o /dev/null -w "%{http_code}" $API/api/auth/me -H "Authorization: Bearer bad.token.here")
check "R2 坏token 401" 401 "$CODE"
R=$(curl -s $API/api/auth/me -H "Authorization: Bearer $EMP_TOKEN")
$PY - "$R" <<'EOF'
import json,sys
d=json.loads(sys.argv[1])
ok = d["code"]==200 and d["data"]["roles"]==["EMPLOYEE"]
print("PASS | R3 角色来自缓存/库（/me 返回最新 EMPLOYEE）" if ok else f"FAIL | R3 {d}")
sys.exit(0 if ok else 1)
EOF
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))

echo "===== R4. 角色变更即时生效（zhangsan 临时绑 ADMIN） ====="
$MYSQL "INSERT INTO sys_user_role(user_id, role_id) VALUES (2, 1)" && echo "  -> 已绑 ADMIN"
R=$(curl -s -X POST $API/api/employees -H "Authorization: Bearer $EMP_TOKEN" \
  -H "Content-Type: application/json" \
  --data-binary '{"empNo":"E301","name":"TempAdmin","deptId":1,"entryDate":"2025-01-01"}')
check "R4a 绑ADMIN未删缓存 403(权限判断走缓存)" 403 $(json_code "$R")
$REDIS_CLI del "user:roles:2" >/dev/null && echo "  -> 已删 user:roles:2 缓存"
R=$(curl -s -X POST $API/api/employees -H "Authorization: Bearer $EMP_TOKEN" \
  -H "Content-Type: application/json" \
  --data-binary '{"empNo":"E302","name":"TempAdmin2","deptId":1,"entryDate":"2025-01-01"}')
check "R4b 删缓存后绑ADMIN生效 200(旧token即时生效)" 200 $(json_code "$R")
$MYSQL "DELETE FROM sys_user_role WHERE user_id=2 AND role_id=1" && echo "  -> 已解除 ADMIN"
$REDIS_CLI del "user:roles:2" >/dev/null && echo "  -> 已删 user:roles:2 缓存"
R=$(curl -s -X POST $API/api/employees -H "Authorization: Bearer $EMP_TOKEN" \
  -H "Content-Type: application/json" \
  --data-binary '{"empNo":"E303","name":"RestoreEmp","deptId":1,"entryDate":"2025-01-01"}')
check "R4c 解除ADMIN后新增员工 403" 403 $(json_code "$R")

echo "===== R5. 登出即失效 ====="
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST $API/api/auth/logout -H "Authorization: Bearer $EMP_TOKEN")
check "R5a 登出接口 200" 200 "$CODE"
CODE=$(curl -s -o /dev/null -w "%{http_code}" $API/api/auth/me -H "Authorization: Bearer $EMP_TOKEN")
check "R5b 登出后旧token调/me 401" 401 "$CODE"
SESS_LEFT=$($REDIS_CLI exists "login:token:$EMP_TOKEN")
check "R5c 会话已删除" 0 "$SESS_LEFT"

echo "===== R6. 服务端踢人（直接删会话） ====="
$REDIS_CLI del "login:token:$ADMIN_TOKEN" >/dev/null
CODE=$(curl -s -o /dev/null -w "%{http_code}" $API/api/auth/me -H "Authorization: Bearer $ADMIN_TOKEN")
check "R6a 删会话后token立即401" 401 "$CODE"

echo "===== R7. 重新登录恢复 ====="
curl -s -X POST $API/api/auth/login -H "Content-Type: application/json" --data-binary @login-zhangsan.json > emp_login.json
EMP_TOKEN=$($PY -c "import json;print(json.load(open('emp_login.json'))['data']['token'])")
CODE=$(curl -s -o /dev/null -w "%{http_code}" $API/api/auth/me -H "Authorization: Bearer $EMP_TOKEN")
check "R7a 重新登录后/me 200" 200 "$CODE"
curl -s -X POST $API/api/auth/login -H "Content-Type: application/json" --data-binary @login-admin.json > admin_login.json

echo
echo "===== 结果：PASS=$PASS FAIL=$FAIL ====="
