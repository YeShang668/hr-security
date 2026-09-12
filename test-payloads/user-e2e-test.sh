#!/bin/bash
# hr-security 第 5 周用户管理专项端到端测试（用户列表 / 启用禁用 / 角色分配 / 禁用踢下线）
# 前置：先跑 e2e-test.sh 生成 admin_login.json/emp_login.json；依赖干净种子数据（zhangsan id=2）
# 环境变量覆盖（供 Docker 环境回归用，见 docker-e2e-test.sh）：
#   API_BASE=接口地址   MYSQL_CMD=数据库命令前缀   REDIS_CLI_CMD=redis 客户端命令前缀   PYTHON=python 解释器
#   注意：本脚本会临时禁用/启用 zhangsan，跑完自动恢复为启用状态
# 用法：bash user-e2e-test.sh
cd "$(dirname "$0")"

# python 解释器探测：Git Bash 下 python 可能被 Windows 应用别名占用（命令存在但执行失败），
# 逐个候选验证可执行性，避免脚本直接报 command not found
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
send_json() { # $1=方法 $2=URL $3=json字符串（TOKEN 环境变量注入）
  $PY - "$1" "$2" "$3" <<'EOF'
import json, sys, subprocess, tempfile, os
method, url, body = sys.argv[1], sys.argv[2], sys.argv[3]
with tempfile.NamedTemporaryFile('w', encoding='utf-8', suffix='.json', delete=False) as f:
    f.write(body); path = f.name
try:
    subprocess.run(['curl', '-s', '-X', method, url,
                    '-H', 'Authorization: Bearer ' + os.environ.get('TOKEN', ''),
                    '-H', 'Content-Type: application/json; charset=utf-8',
                    '--data-binary', '@' + path], check=False)
finally:
    os.unlink(path)
EOF
}
put_json() { send_json PUT "$1" "$2"; }

echo "===== U. 用户管理接口（仅 ADMIN） ====="
R=$(curl -s "$API/api/users?page=1&size=10" -H "Authorization: Bearer $ADMIN_TOKEN")
$PY - "$R" <<'EOF'
import json,sys
d=json.loads(sys.argv[1])
recs = d["data"]["records"] if d["code"]==200 else []
by_name = {r["username"]: r for r in recs}
ok = (d["code"]==200 and d["data"]["total"]>=2
      and "password" not in json.dumps(d)          # 出参绝不能带密码字段
      and by_name.get("admin",{}).get("roles")==["ADMIN"]
      and by_name.get("zhangsan",{}).get("roles")==["EMPLOYEE"])
print("PASS | U1 用户列表(code/角色/无密码字段)" if ok else f"FAIL | U1 {d}")
sys.exit(0 if ok else 1)
EOF
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))
R=$(curl -s "$API/api/users?keyword=zhang" -H "Authorization: Bearer $ADMIN_TOKEN")
check "U2 关键字过滤(zhang→200)" 200 $(json_code "$R")
R=$(curl -s "$API/api/users" -H "Authorization: Bearer $EMP_TOKEN")
check "U3 EMPLOYEE 查用户列表 403" 403 $(json_code "$R")
R=$(curl -s "$API/api/roles" -H "Authorization: Bearer $ADMIN_TOKEN")
check "U4 角色列表(ADMIN)" 200 $(json_code "$R")
R=$(curl -s "$API/api/roles" -H "Authorization: Bearer $EMP_TOKEN")
check "U5 EMPLOYEE 查角色列表 403" 403 $(json_code "$R")

echo "===== U6. 管理员自我保护 ====="
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/users/1/status" '{"status":0}')
check "U6a 禁用自己 400" 400 $(json_code "$R")
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/users/1/roles" '{"roleIds":[2]}')
check "U6b 改自己角色 400" 400 $(json_code "$R")
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/users/2/status" '{"status":9}')
check "U6c status 越界 400" 400 $(json_code "$R")
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/users/2/roles" '{"roleIds":[999]}')
check "U6d 角色不存在 400" 400 $(json_code "$R")
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/users/2/roles" '{"roleIds":[]}')
check "U6e 空角色列表 400" 400 $(json_code "$R")
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/users/99999/status" '{"status":0}')
check "U6f 用户不存在 404" 404 $(json_code "$R")
R=$(TOKEN=$EMP_TOKEN put_json "$API/api/users/2/status" '{"status":0}')
check "U6g EMPLOYEE 禁用他人 403" 403 $(json_code "$R")

echo "===== U7. 角色分配即时生效（旧 token 免重登） ====="
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/users/2/roles" '{"roleIds":[1,2]}')
check "U7a 给 zhangsan 加 ADMIN" 200 $(json_code "$R")
R=$(curl -s -o /dev/null -w "%{http_code}" "$API/api/users" -H "Authorization: Bearer $EMP_TOKEN")
check "U7b 旧 token 立即获得 ADMIN(200)" 200 "$R"
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/users/2/roles" '{"roleIds":[2]}')
check "U7c 收回 ADMIN" 200 $(json_code "$R")
R=$(curl -s "$API/api/users" -H "Authorization: Bearer $EMP_TOKEN")
check "U7d 旧 token 立即失去权限(403)" 403 $(json_code "$R")

echo "===== U8. 禁用即踢下线 ====="
TTL=$($REDIS_CLI ttl "login:user:2")
[ "$TTL" -gt 85000 ] && { echo "PASS | U8a 用户会话索引 login:user:2 存在(TTL=${TTL}s)"; PASS=$((PASS+1)); } \
  || { echo "FAIL | U8a login:user:2 TTL=$TTL"; FAIL=$((FAIL+1)); }
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$API/api/auth/me" -H "Authorization: Bearer $EMP_TOKEN")
check "U8b 禁用前 /me 200" 200 "$CODE"
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/users/2/status" '{"status":0}')
check "U8c 禁用 zhangsan" 200 $(json_code "$R")
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$API/api/auth/me" -H "Authorization: Bearer $EMP_TOKEN")
check "U8d 被禁用后旧 token 立即 401(踢下线)" 401 "$CODE"
SESS=$($REDIS_CLI exists "login:user:2")
check "U8e 会话索引已清除" 0 "$SESS"
R=$(curl -s -X POST $API/api/auth/login -H "Content-Type: application/json" --data-binary @login-zhangsan.json)
check "U8f 禁用账号无法登录 403" 403 $(json_code "$R")

echo "===== U9. 恢复启用 ====="
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/users/2/status" '{"status":1}')
check "U9a 启用 zhangsan" 200 $(json_code "$R")
curl -s -X POST $API/api/auth/login -H "Content-Type: application/json" --data-binary @login-zhangsan.json > emp_login.json
NEW_EMP_TOKEN=$($PY -c "import json;print(json.load(open('emp_login.json'))['data']['token'])")
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$API/api/auth/me" -H "Authorization: Bearer $NEW_EMP_TOKEN")
check "U9b 启用后可正常登录访问" 200 "$CODE"
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$API/api/users" -H "Authorization: Bearer $ADMIN_TOKEN")
check "U9c admin 会话未被误伤" 200 "$CODE"

echo
echo "===== 结果：PASS=$PASS FAIL=$FAIL ====="
