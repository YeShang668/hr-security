#!/bin/bash
# hr-security 第 2 周端到端测试脚本（RBAC + 部门/员工 CRUD + 接口权限）
# 中文请求体统一由 python 写 UTF-8 临时文件再发送，避免 Git Bash 参数转 GBK 乱码
# 环境变量覆盖：API_BASE（默认 http://localhost:8080，Docker 环境无需改，compose 同样映射 8080）
cd "$(dirname "$0")"

# python 解释器探测（BUG5-4）：Git Bash 里 python 可能指向 Windows 应用别名占位程序，
# 命令存在但执行直接失败；逐个候选验证可执行性，避免脚本报 command not found
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
PASS=0; FAIL=0
check() { # $1=用例名 $2=期望code $3=实际code
  if [ "$3" = "$2" ]; then echo "PASS | $1"; PASS=$((PASS+1));
  else echo "FAIL | $1 (期望 code=$2 实际 code=$3)"; FAIL=$((FAIL+1)); fi
}
json_code() { $PY -c "import json,sys;print(json.loads(sys.argv[1])['code'])" "$1"; }
send_json() { # $1=方法 $2=URL $3=json字符串 → 输出响应（TOKEN 环境变量注入）
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
post_json() { send_json POST "$1" "$2"; }
put_json() { send_json PUT "$1" "$2"; }

echo "===== A. 认证与角色 ====="
CODE=$(curl -s -o /dev/null -w "%{http_code}" $API/api/depts)
[ "$CODE" = "401" ] && { echo "PASS | A1 无token访问部门列表 401"; PASS=$((PASS+1)); } || { echo "FAIL | A1 期望401 实际$CODE"; FAIL=$((FAIL+1)); }
R=$(curl -s $API/api/auth/me -H "Authorization: Bearer $EMP_TOKEN")
$PY - "$R" <<'EOF'
import json,sys
d=json.loads(sys.argv[1])
ok = d["code"]==200 and d["data"]["roles"]==["EMPLOYEE"]
print("PASS | A2 /me 返回 EMPLOYEE 角色" if ok else f"FAIL | A2 {d}")
sys.exit(0 if ok else 1)
EOF
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))

echo "===== B. 部门 CRUD（admin） ====="
R=$(TOKEN=$ADMIN_TOKEN post_json $API/api/depts '{"deptName":"测试部","sort":9}')
check "B1 新增部门" 200 $(json_code "$R")
DEPT_ID=$($PY -c "import json,sys;d=json.loads(sys.argv[1]);print(d['data']['id'] if d.get('data') else 0)" "$R")
R=$(curl -s $API/api/depts -H "Authorization: Bearer $ADMIN_TOKEN")
check "B2 部门列表" 200 $(json_code "$R")
R=$(TOKEN=$ADMIN_TOKEN put_json $API/api/depts/$DEPT_ID '{"deptName":"测试研发部","sort":8}')
check "B3 修改部门" 200 $(json_code "$R")
R=$(curl -s -X DELETE $API/api/depts/$DEPT_ID -H "Authorization: Bearer $ADMIN_TOKEN")
check "B4 删除空部门" 200 $(json_code "$R")

echo "===== C. 员工 CRUD（admin） ====="
R=$(TOKEN=$ADMIN_TOKEN post_json $API/api/employees '{"empNo":"E100","name":"测试员工","gender":1,"phone":"13900000000","email":"test@hr.com","deptId":1,"entryDate":"2025-05-01"}')
check "C1 新增员工" 200 $(json_code "$R")
EMP_ID=$($PY -c "import json,sys;d=json.loads(sys.argv[1]);print(d['data']['id'] if d.get('data') else 0)" "$R")
R=$(TOKEN=$ADMIN_TOKEN post_json $API/api/employees '{"empNo":"E100","name":"重复工号","deptId":1}')
check "C2 重复工号 409" 409 $(json_code "$R")
R=$(curl -s "$API/api/employees?page=1&size=10&keyword=%E6%B5%8B%E8%AF%95" -H "Authorization: Bearer $ADMIN_TOKEN")
$PY - "$R" <<'EOF'
import json,sys
d=json.loads(sys.argv[1])
ok = d["code"]==200 and d["data"]["total"]>=1 and d["data"]["records"][0]["deptName"]=="技术部"
print("PASS | C3 分页+keyword+deptName联查" if ok else f"FAIL | C3 {d}")
sys.exit(0 if ok else 1)
EOF
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))
R=$(TOKEN=$ADMIN_TOKEN put_json $API/api/employees/$EMP_ID '{"empNo":"E100","name":"测试员工改","gender":2,"deptId":2}')
check "C4 修改员工" 200 $(json_code "$R")
R=$(TOKEN=$ADMIN_TOKEN post_json $API/api/employees '{"empNo":"E101","name":"孤儿员工","deptId":9999}')
check "C5 部门不存在 400" 400 $(json_code "$R")
R=$(curl -s -X DELETE $API/api/depts/1 -H "Authorization: Bearer $ADMIN_TOKEN")
check "C6 删除有员工部门 409" 409 $(json_code "$R")
R=$(curl -s -X DELETE $API/api/employees/$EMP_ID -H "Authorization: Bearer $ADMIN_TOKEN")
check "C7 删除员工(逻辑)" 200 $(json_code "$R")
R=$(curl -s "$API/api/employees?page=1&size=10" -H "Authorization: Bearer $ADMIN_TOKEN")
$PY - "$R" "$EMP_ID" <<'EOF'
import json,sys
d=json.loads(sys.argv[1]); eid=int(sys.argv[2])
ids=[r["id"] for r in d["data"]["records"]]
ok = eid not in ids
print("PASS | C8 离职员工被过滤" if ok else f"FAIL | C8 员工{eid}仍出现: {ids}")
sys.exit(0 if ok else 1)
EOF
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))
R=$(TOKEN=$ADMIN_TOKEN put_json $API/api/employees/99999 '{"empNo":"E999","name":"不存在","deptId":1}')
check "C9 修改不存在员工 404" 404 $(json_code "$R")

echo "===== D. 接口权限（EMPLOYEE 角色） ====="
R=$(TOKEN=$EMP_TOKEN post_json $API/api/employees '{"empNo":"E200","name":"越权员工","deptId":1}')
check "D1 员工角色新增员工 403" 403 $(json_code "$R")
R=$(curl -s -X DELETE $API/api/depts/2 -H "Authorization: Bearer $EMP_TOKEN")
check "D2 员工角色删除部门 403" 403 $(json_code "$R")
R=$(TOKEN=$EMP_TOKEN put_json $API/api/employees/1 '{"empNo":"E1","name":"越权修改","deptId":1}')
check "D3 员工角色修改员工 403" 403 $(json_code "$R")
R=$(curl -s $API/api/depts -H "Authorization: Bearer $EMP_TOKEN")
check "D4 员工角色查看部门 200" 200 $(json_code "$R")
R=$(curl -s "$API/api/employees?page=1&size=10" -H "Authorization: Bearer $EMP_TOKEN")
check "D5 员工角色查看员工 200" 200 $(json_code "$R")

echo
echo "===== 结果：PASS=$PASS FAIL=$FAIL ====="
