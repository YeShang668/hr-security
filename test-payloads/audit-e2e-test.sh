#!/bin/bash
# hr-security 第 7 周审计日志专项端到端测试（AOP 埋点 + 异步落库 + 查询接口 + 权限边界）
#
# 前置：先跑 e2e-test.sh 生成 admin_login.json/emp_login.json；依赖干净种子数据（zhangsan id=2）
# 环境变量覆盖（供 Docker 环境回归用，见 docker-e2e-test.sh）：
#   API_BASE=接口地址   MYSQL_CMD=数据库命令前缀   PYTHON=python 解释器
# 副作用与自恢复：本脚本会临时禁用/改角色 zhangsan，结尾恢复为启用 + EMPLOYEE，并重刷 emp_login.json
# 用法：bash audit-e2e-test.sh
#
# 关于"轮询等审计落库"（本脚本的关键测试手法）：
#   审计是**异步写库**（@Async + 自定义线程池），接口返回时审计行可能还没落库。
#   所以断言不能"发完请求立刻查库"，也不能"sleep 3 秒靠猜"——
#   统一用 wait_sql 轮询（最长 6s）直到 SQL 结果符合期望。
#   这本身就是异步设计对测试的影响，写进 docs/audit-design.md 备查。
cd "$(dirname "$0")"

# python 解释器探测（BUG5-4，与其它脚本一致）
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
PASS=0; FAIL=0

check() { if [ "$3" = "$2" ]; then echo "PASS | $1"; PASS=$((PASS+1));
  else echo "FAIL | $1 (期望=$2 实际=$3)"; FAIL=$((FAIL+1)); fi }
json_code() { $PY -c "import json,sys;print(json.loads(sys.argv[1])['code'])" "$1"; }
sql_one() { $MYSQL "$1" | tail -n +2 | tr -d '\r' | head -1; }
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
post_json() { send_json POST "$1" "$2"; }
# 把"上一条 python 断言"的退出码计入 PASS/FAIL（与 crypto 脚本同一写法）
# 踩坑记录（BUG7-8）：本脚本一开始漏定义这个函数，A4 改成 python 断言后就变成
# "命令找不到"——既没 PASS 也没 FAIL，总数悄悄少 1（49 而不是 50），
# 只有"期望用例数"校验才能发现这类静默丢用例。
label_ok() { if [ $? -eq 0 ]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi; }

# 轮询等待异步审计落库：$1=用例名 $2=期望值 $3=SQL $4=超时秒数(默认 10)
# 超时给 10s：容器环境下每条 SQL 都要经 docker compose exec（约 1s/次），轮询次数会比本地少，
# 留足余量避免"异步写入稍慢就误判失败"的抖动（BUG7-7 一并记录）。
wait_sql() {
  local name="$1" expect="$2" sql="$3" timeout="${4:-10}" got="" deadline
  deadline=$(( $(date +%s) + timeout ))
  while [ "$(date +%s)" -le "$deadline" ]; do
    got=$($MYSQL "$sql" | tail -n +2 | tr -d '\r' | head -1)
    [ "$got" = "$expect" ] && break
    sleep 0.3
  done
  check "$name" "$expect" "$got"
}

E1ID=$(sql_one "SELECT id FROM sys_employee WHERE emp_no='E001'")
[ -z "$E1ID" ] && { echo "种子员工 E001 不存在，请先执行 sql/init.sql"; exit 1; }
CNT_SENSITIVE="SELECT COUNT(*) FROM sys_audit_log WHERE operation='查看员工敏感信息'"

echo "===== A. 明文查看留痕（本周最重要的一条） ====="
# 先确保 E001 有敏感数据（幂等），这样审计内容才有真实对象
curl -s -X POST $API/api/admin/crypto/backfill -H "Authorization: Bearer $ADMIN_TOKEN" >/dev/null
N0=$($MYSQL "$CNT_SENSITIVE" | tail -n +2 | tr -d '\r')
R=$(curl -s "$API/api/employees/$E1ID/sensitive" -H "Authorization: Bearer $ADMIN_TOKEN")
check "A1 ADMIN 查看敏感明文 200" 200 $(json_code "$R")
wait_sql "A2 查看明码后审计表增行（$N0 → $((N0+1))）" "$((N0+1))" "$CNT_SENSITIVE"
ROW=$($MYSQL "SELECT CONCAT_WS('|', user_id, username, operation, target_type, target_id, result) FROM sys_audit_log WHERE operation='查看员工敏感信息' ORDER BY id DESC LIMIT 1" | tail -n +2 | tr -d '\r')
check "A3 操作者/对象/结果齐全" "1|admin|查看员工敏感信息|EMPLOYEE|$E1ID|SUCCESS" "$ROW"
IP=$($MYSQL "SELECT ip FROM sys_audit_log WHERE operation='查看员工敏感信息' ORDER BY id DESC LIMIT 1" | tail -n +2 | tr -d '\r')
# 注意（BUG7-7）：这里刻意**不**断言 ip 是 127.0.0.1。
# 本地跑（jar 在宿主机）直连确实是回环地址，但容器环境里请求经 docker-proxy 进来，
# 应用看到的对端是 Docker 网关地址（如 172.18.0.1）——那是**正确**的记录结果。
# 所以断言"记录了合法的 IP 且非空/非 unknown"，具体地址由环境决定；
# "代理链取最左真实客户端"这条语义由 A8 用显式 X-Forwarded-For 覆盖。
$PY - "$IP" <<'EOF'
import re, sys
ip = (sys.argv[1] or "").strip()
# IPv4 或 IPv6（含 ::1 / 0:0:0:0:0:0:0:1 / 172.18.0.1）
pattern = re.compile(r'^(\d{1,3}(\.\d{1,3}){3}|[0-9a-fA-F:]{2,45})$')
ok = bool(ip) and ip.lower() != "unknown" and bool(pattern.match(ip))
print(f"PASS | A4 直连场景记录了合法客户端 IP（ip={ip}）" if ok else f"FAIL | A4 ip 异常：{ip!r}")
sys.exit(0 if ok else 1)
EOF
label_ok
DET=$($MYSQL "SELECT COUNT(*) FROM sys_audit_log WHERE operation='查看员工敏感信息' AND (detail LIKE '%110101199003071234%' OR detail LIKE '%13800000001%' OR detail LIKE '%6222020200112233445%' OR detail LIKE '%18000.00%')" | tail -n +2 | tr -d '\r')
check "A5 审计 detail 不含任何敏证明文（0 行命中）" 0 "$DET"
CUR=$($MYSQL "$CNT_SENSITIVE" | tail -n +2 | tr -d '\r')
R=$(curl -s "$API/api/employees/$E1ID/sensitive" -H "Authorization: Bearer $ADMIN_TOKEN" \
      -H "X-Forwarded-For: 203.0.113.9, 10.0.0.1" -H "User-Agent: HrSecAuditTest/1.0")
check "A6 带代理链头查看明文 200" 200 $(json_code "$R")
wait_sql "A7 审计增行（代理场景）" "$((CUR+1))" "$CNT_SENSITIVE"
XFF=$($MYSQL "SELECT ip FROM sys_audit_log WHERE operation='查看员工敏感信息' ORDER BY id DESC LIMIT 1" | tail -n +2 | tr -d '\r')
check "A8 X-Forwarded-For 取最左=真实客户端（非网关 IP）" "203.0.113.9" "$XFF"
UA=$($MYSQL "SELECT user_agent FROM sys_audit_log WHERE operation='查看员工敏感信息' ORDER BY id DESC LIMIT 1" | tail -n +2 | tr -d '\r')
check "A9 User-Agent 被记录" "HrSecAuditTest/1.0" "$UA"

echo "===== B. 越权尝试：不产生成功审计记录 ====="
R=$(curl -s "$API/api/employees/$E1ID/sensitive" -H "Authorization: Bearer $EMP_TOKEN")
check "B1 EMPLOYEE 取明文 403（@PreAuthorize 在方法前拦下）" 403 $(json_code "$R")
sleep 1
SUCC_EMP=$($MYSQL "SELECT COUNT(*) FROM sys_audit_log WHERE operation='查看员工敏感信息' AND username='zhangsan' AND result='SUCCESS'" | tail -n +2 | tr -d '\r')
check "B2 无权限者没有被记成'看成了'（0 条 SUCCESS）" 0 "$SUCC_EMP"
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$API/api/employees/$E1ID/sensitive")
check "B3 未登录取明文 401" 401 "$CODE"

echo "===== C. 权限变更与运维动作留痕 ====="
CUR=$($MYSQL "SELECT COUNT(*) FROM sys_audit_log WHERE operation='变更账号状态'" | tail -n +2 | tr -d '\r')
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/users/2/status" '{"status":0}')
check "C1a 禁用 zhangsan 200" 200 $(json_code "$R")
wait_sql "C1b 禁用动作被审计（增行）" "$((CUR+1))" "SELECT COUNT(*) FROM sys_audit_log WHERE operation='变更账号状态'"
ROW=$($MYSQL "SELECT CONCAT_WS('|', username, target_type, target_id, result) FROM sys_audit_log WHERE operation='变更账号状态' ORDER BY id DESC LIMIT 1" | tail -n +2 | tr -d '\r')
check "C1c 记录操作者与目标账号" "admin|USER|2|SUCCESS" "$ROW"
DET=$($MYSQL "SELECT detail FROM sys_audit_log WHERE operation='变更账号状态' ORDER BY id DESC LIMIT 1" | tail -n +2 | tr -d '\r')
case "$DET" in
  *"禁用(0)"*) echo "PASS | C1d 运行时细节入库（detail 含 禁用(0)）"; PASS=$((PASS+1));;
  *) echo "FAIL | C1d detail=$DET"; FAIL=$((FAIL+1));;
esac
CUR=$($MYSQL "SELECT COUNT(*) FROM sys_audit_log WHERE operation='分配用户角色'" | tail -n +2 | tr -d '\r')
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/users/2/roles" '{"roleIds":[1,2]}')
check "C2a 给 zhangsan 分配 ADMIN+EMPLOYEE 200" 200 $(json_code "$R")
wait_sql "C2b 角色分配被审计（增行）" "$((CUR+1))" "SELECT COUNT(*) FROM sys_audit_log WHERE operation='分配用户角色'"
DET=$($MYSQL "SELECT detail FROM sys_audit_log WHERE operation='分配用户角色' ORDER BY id DESC LIMIT 1" | tail -n +2 | tr -d '\r')
case "$DET" in
  *"ADMIN"*) echo "PASS | C2c detail 记录被授予的角色编码（提权可追责）"; PASS=$((PASS+1));;
  *) echo "FAIL | C2c detail=$DET"; FAIL=$((FAIL+1));;
esac
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/users/2/roles" '{"roleIds":[2]}')
check "C2d 收回 ADMIN（恢复 EMPLOYEE）" 200 $(json_code "$R")
CUR=$($MYSQL "SELECT COUNT(*) FROM sys_audit_log WHERE operation='变更账号状态' AND result='FAILURE'" | tail -n +2 | tr -d '\r')
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/users/1/status" '{"status":0}')
check "C3a 禁用自己被红线拦下 400" 400 $(json_code "$R")
wait_sql "C3b 失败动作同样留痕（FAILURE 增行）" "$((CUR+1))" "SELECT COUNT(*) FROM sys_audit_log WHERE operation='变更账号状态' AND result='FAILURE'"
DET=$($MYSQL "SELECT detail FROM sys_audit_log WHERE result='FAILURE' ORDER BY id DESC LIMIT 1" | tail -n +2 | tr -d '\r')
case "$DET" in
  *"失败原因"*) echo "PASS | C3c FAILURE 记录带失败原因"; PASS=$((PASS+1));;
  *) echo "FAIL | C3c detail=$DET"; FAIL=$((FAIL+1));;
esac
CUR=$($MYSQL "SELECT COUNT(*) FROM sys_audit_log WHERE operation='历史数据加密迁移'" | tail -n +2 | tr -d '\r')
R=$(TOKEN=$ADMIN_TOKEN post_json "$API/api/admin/crypto/backfill" '')
check "C4a 触发加密迁移 200" 200 $(json_code "$R")
wait_sql "C4b 数据级运维动作被审计" "$((CUR+1))" "SELECT COUNT(*) FROM sys_audit_log WHERE operation='历史数据加密迁移'"
ROW=$($MYSQL "SELECT CONCAT_WS('|', target_type, result) FROM sys_audit_log WHERE operation='历史数据加密迁移' ORDER BY id DESC LIMIT 1" | tail -n +2 | tr -d '\r')
check "C4c 审计记录对象类型与结果" "CRYPTO|SUCCESS" "$ROW"
DET=$($MYSQL "SELECT detail FROM sys_audit_log WHERE operation='历史数据加密迁移' ORDER BY id DESC LIMIT 1" | tail -n +2 | tr -d '\r')
case "$DET" in
  *"migrated"*) echo "PASS | C4d 执行报告写进审计 detail（不必翻应用日志）"; PASS=$((PASS+1));;
  *) echo "FAIL | C4d detail=$DET"; FAIL=$((FAIL+1));;
esac
$MYSQL "DELETE FROM sys_employee WHERE emp_no='E300'" >/dev/null 2>&1
CUR=$($MYSQL "SELECT COUNT(*) FROM sys_audit_log WHERE operation='新增员工'" | tail -n +2 | tr -d '\r')
R=$(TOKEN=$ADMIN_TOKEN post_json "$API/api/employees" '{"empNo":"E300","name":"审计测试员工","gender":1,"phone":"13600002222","deptId":1,"entryDate":"2026-01-01"}')
check "C5a 新增员工（含敏感字段）200" 200 $(json_code "$R")
NEWID=$($PY -c "import json,sys;d=json.loads(sys.argv[1]);print((d.get('data') or {}).get('id',0))" "$R")
wait_sql "C5b 新增动作被审计（增行）" "$((CUR+1))" "SELECT COUNT(*) FROM sys_audit_log WHERE operation='新增员工'"
TID=$($MYSQL "SELECT target_id FROM sys_audit_log WHERE operation='新增员工' ORDER BY id DESC LIMIT 1" | tail -n +2 | tr -d '\r')
check "C5c 目标 id 从返回值取到（新增也能定位到新记录）" "$NEWID" "$TID"
DET=$($MYSQL "SELECT detail FROM sys_audit_log WHERE operation='新增员工' ORDER BY id DESC LIMIT 1" | tail -n +2 | tr -d '\r')
if echo "$DET" | grep -q "敏感字段" && echo "$DET" | grep -q "手机号" && ! echo "$DET" | grep -q "13600002222"; then
  echo "PASS | C5d 敏感字段只记字段名、不记值（detail 含'手机号'但无明文）"; PASS=$((PASS+1));
else
  echo "FAIL | C5d detail=$DET"; FAIL=$((FAIL+1));
fi
DTEST=$($MYSQL "SELECT COUNT(*) FROM sys_audit_log WHERE operation='删除员工（离职）'" | tail -n +2 | tr -d '\r')
R=$(curl -s -X DELETE "$API/api/employees/$NEWID" -H "Authorization: Bearer $ADMIN_TOKEN")
check "C6a 删除员工（逻辑删除）200" 200 $(json_code "$R")
wait_sql "C6b 删除动作被审计" "$((DTEST+1))" "SELECT COUNT(*) FROM sys_audit_log WHERE operation='删除员工（离职）'"

echo "===== D. 恢复现场（C 段把 zhangsan 禁用/改过角色，后面的权限用例需要有效 EMPLOYEE token） ====="
# 踩坑记录（BUG7-2）：C1a 禁用 zhangsan 时会**立即删除他的全部登录会话**（禁用即踢下线），
# 手里的旧 EMPLOYEE token 从此就是 401 而不是 403。
# 因此"EMPLOYEE 查审计应 403"必须用**重新登录后的新 token**，
# 否则拿到的是 401（未认证），用例看似测权限、实际测的是失效——断言写 403 就会误报失败。
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/users/2/status" '{"status":1}')
check "D1 恢复 zhangsan 启用状态" 200 $(json_code "$R")
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/users/2/roles" '{"roleIds":[2]}')
check "D2 恢复 zhangsan 角色为 EMPLOYEE" 200 $(json_code "$R")
curl -s -X POST $API/api/auth/login -H "Content-Type: application/json" --data-binary @login-zhangsan.json > emp_login.json
EMP_TOKEN=$($PY -c "import json;print(json.load(open('emp_login.json'))['data']['token'])")
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$API/api/auth/me" -H "Authorization: Bearer $EMP_TOKEN")
check "D3 重新登录后 zhangsan 可正常访问（新 token 生效）" 200 "$CODE"

echo "===== E. 审计查询接口 ====="
R=$(curl -s "$API/api/audit-logs?page=1&size=10" -H "Authorization: Bearer $ADMIN_TOKEN")
$PY - "$R" <<'EOF'
import json,sys
d=json.loads(sys.argv[1])
recs = d["data"]["records"] if d["code"]==200 else []
first = recs[0] if recs else {}
ids = [r["id"] for r in recs]
need = {"id","userId","username","operation","targetType","targetId","detail","result","ip","userAgent","createdAt"}
ok = (d["code"]==200 and d["data"]["total"]>0 and len(recs)>0
      and need.issubset(set(first.keys()))
      and ids == sorted(ids, reverse=True))     # 倒序：最新事件在最上面
print("PASS | E1 审计列表（分页结构/字段齐全/按 id 倒序）" if ok else f"FAIL | E1 {d}")
sys.exit(0 if ok else 1)
EOF
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))
R=$(curl -s "$API/api/audit-logs" -H "Authorization: Bearer $EMP_TOKEN")
check "E2 EMPLOYEE 查审计日志 403（权限是审计记录的第一道保护）" 403 $(json_code "$R")
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$API/api/audit-logs")
check "E3 未登录查审计日志 401" 401 "$CODE"
R=$(curl -s "$API/api/audit-logs?username=admin&size=50" -H "Authorization: Bearer $ADMIN_TOKEN")
OTHERS=$($PY -c "import json,sys;d=json.loads(sys.argv[1]);print(sum(1 for r in d['data']['records'] if r['username']!='admin'))" "$R")
check "E4 按用户过滤（username=admin，无他人记录）" 0 "$OTHERS"
R=$(curl -s "$API/api/audit-logs?operation=%E6%95%8F%E6%84%9F&size=50" -H "Authorization: Bearer $ADMIN_TOKEN")
NOMATCH=$($PY -c "import json,sys;d=json.loads(sys.argv[1]);print(sum(1 for r in d['data']['records'] if '敏感' not in r['operation']))" "$R")
TOTAL=$($PY -c "import json,sys;print(json.loads(sys.argv[1])['data']['total'])" "$R")
check "E5 按操作类型过滤（operation 含'敏感'）" 0 "$NOMATCH"
[ "$TOTAL" -gt 0 ] && { echo "PASS | E6 过滤命中（total=$TOTAL）"; PASS=$((PASS+1)); } || { echo "FAIL | E6 过滤 total=$TOTAL"; FAIL=$((FAIL+1)); }
R=$(curl -s "$API/api/audit-logs?result=FAILURE&size=50" -H "Authorization: Bearer $ADMIN_TOKEN")
NOTFAIL=$($PY -c "import json,sys;d=json.loads(sys.argv[1]);print(sum(1 for r in d['data']['records'] if r['result']!='FAILURE'))" "$R")
check "E7 只看失败（result=FAILURE）" 0 "$NOTFAIL"
R=$(curl -s "$API/api/audit-logs?page=2&size=1" -H "Authorization: Bearer $ADMIN_TOKEN")
LEN=$($PY -c "import json,sys;d=json.loads(sys.argv[1]);print(len(d['data']['records']))" "$R")
check "E8 分页生效（size=1 每页 1 条）" 1 "$LEN"
CUR=$($MYSQL "SELECT COUNT(*) FROM sys_audit_log WHERE operation='查询审计日志'" | tail -n +2 | tr -d '\r')
curl -s "$API/api/audit-logs?size=1" -H "Authorization: Bearer $ADMIN_TOKEN" >/dev/null
wait_sql "E9 查询审计日志本身也留痕（翻账要留痕）" "$((CUR+1))" "SELECT COUNT(*) FROM sys_audit_log WHERE operation='查询审计日志'"
R=$(curl -s "$API/api/audit-logs?size=100000" -H "Authorization: Bearer $ADMIN_TOKEN")
LEN=$($PY -c "import json,sys;d=json.loads(sys.argv[1]);print(len(d['data']['records']))" "$R")
[ "$LEN" -le 100 ] && { echo "PASS | E10 单页上限生效（size 被截断到 ≤100，实测 $LEN）"; PASS=$((PASS+1)); } \
  || { echo "FAIL | E10 单页返回 $LEN 条"; FAIL=$((FAIL+1)); }

echo "===== F. 异步写入：不拖慢接口、不丢记录 ====="
CUR=$($MYSQL "SELECT COUNT(*) FROM sys_audit_log" | tail -n +2 | tr -d '\r')
START=$(date +%s%3N)
for i in $(seq 1 10); do
  curl -s "$API/api/employees/$E1ID/sensitive" -H "Authorization: Bearer $ADMIN_TOKEN" >/dev/null
done
ELAPSED=$(( $(date +%s%3N) - START ))
wait_sql "F1 连续 10 次敏感查看全部落库（异步不丢）" "$((CUR+10))" "SELECT COUNT(*) FROM sys_audit_log"
# 阈值给 15s：这条只用于兜住"审计把接口拖到不可接受"的明显退化（本地实测约 0.8s，容器环境含 docker-proxy 转发会更慢），
# 不做精细性能门禁（性能基线在第 8 周压测里单独做）
[ "$ELAPSED" -lt 15000 ] && { echo "PASS | F2 10 次调用总耗时 ${ELAPSED}ms（审计未把接口拖到不可接受）"; PASS=$((PASS+1)); } \
  || { echo "FAIL | F2 10 次调用耗时 ${ELAPSED}ms"; FAIL=$((FAIL+1)); }

echo "===== G. 现场确认（脚本可重复运行的收尾状态） ====="
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$API/api/auth/me" -H "Authorization: Bearer $ADMIN_TOKEN")
check "G1 admin 会话未受影响" 200 "$CODE"
ST=$($MYSQL "SELECT status FROM sys_user WHERE id=2" | tail -n +2 | tr -d '\r')
check "G2 zhangsan 状态已恢复为启用(1)" 1 "$ST"

echo
echo "===== 结果：PASS=$PASS FAIL=$FAIL ====="
