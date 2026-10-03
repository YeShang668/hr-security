#!/bin/bash
# hr-security 第 8 周安全加固专项端到端测试
#   V 垂直越权矩阵 / H 水平越权与数据边界 / I 注入 / X XSS / S 安全响应头与 CSRF 适用性 / B 登录防护
#
# 前置：先跑 e2e-test.sh 生成 admin_login.json/emp_login.json；依赖干净种子数据（zhangsan id=2）
# 环境变量覆盖（与其它脚本一致，供 Docker/HTTPS 环境复用）：
#   API_BASE=接口地址（本地 http://localhost:8080；容器走 https://localhost）  MYSQL_CMD  REDIS_CLI_CMD  PYTHON
# 副作用与自恢复：探针数据统一带 SEC 前缀（不复用真实账号做爆破），结尾清空 Redis 登录失败计数。
# 用法：bash security-e2e-test.sh
#
# 两个测试约定（沿用第 7 周的教训，见 docs/week7-bugfix-log.md）：
#   1. **断言盯语义**：不写"直连 IP 必须是回环地址"这类与网络拓扑耦合的断言；
#   2. **用例数也要断言**：总控脚本按期望数核对 PASS，防止用例被静默跳过。
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"

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
REDIS_CLI=${REDIS_CLI_CMD:-/e/devlop/redis-windows-8.10.1/Redis-8.10.1-Windows-x64-msys2/redis-cli.exe}
export MYSQL_CMD="$MYSQL"
PASS=0; FAIL=0

check() { if [ "$3" = "$2" ]; then echo "PASS | $1"; PASS=$((PASS+1));
  else echo "FAIL | $1 (期望=$2 实际=$3)"; FAIL=$((FAIL+1)); fi }
# $2 是候选值列表（空格分隔），命中任一即通过：用于"创建成功或已存在都算符合预期"这类幂等场景
check_in() { local n="$1" want="$2" got="$3"; case " $want " in *" $got "*) echo "PASS | $n"; PASS=$((PASS+1));;
  *) echo "FAIL | $n (期望∈[$want] 实际=$got)"; FAIL=$((FAIL+1));; esac }
json_code() { $PY -c "import json,sys;print(json.loads(sys.argv[1])['code'])" "$1"; }
# 取响应里的任意字段（第 3 个参数是 python 表达式，d = 已解析的响应体）
json_field() { $PY -c "import json,sys;d=json.loads(sys.argv[1]);print(eval(sys.argv[2]))" "$1" "$2" 2>/dev/null; }
http_code() { curl -s -o /dev/null -w "%{http_code}" "$@"; }
# 中文请求体必须由 python 写 UTF-8 临时文件再 --data-binary 发送（TOKEN 环境变量注入）。
# 直接用 curl -d '{"name":"中文"}' 时，Git Bash 会把内联中文按 GBK 字节发出去，
# 后端 Jackson 解析 UTF-8 失败 → 400/500 —— 那不是权限或校验的问题，是测试脚手架的问题。
# 本次踩坑记录：H1/B10 一开始就是这么假失败成 500 的（见 docs/week8-bugfix-log.md BUG8-1）。
send_json() { # $1=方法 $2=URL $3=json字符串
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
post_anon_json() { send_json POST "$1" "$2"; }   # 注册/登录等白名单接口（无 token 同样可发）
# 某响应头必填值（$1=头名 $2=URL $3=附加请求头）
header_of() { curl -s -D - -o /dev/null ${3:+-H "$3"} "$2" | grep -i "^$1:" | tr -d '\r' | sed "s/^[^:]*: *//I"; }
# 该响应头出现了几次（用于"HSTS 只在 https 下发"这类存在性断言）
header_count() { curl -s -D - -o /dev/null ${2:+-H "$2"} "$1" | grep -ci "strict-transport-security"; }

echo "===== V. 垂直越权矩阵（未登录 / EMPLOYEE × 各类接口）====="
check "V1  未登录 GET /api/employees → HTTP 401" 401 "$(http_code $API/api/employees)"
check "V2  未登录 PUT /api/users/1/status → HTTP 401" 401 \
  "$(http_code -X PUT $API/api/users/1/status -H 'Content-Type: application/json' -d '{"status":0}')"
check "V3  未登录 GET /api/admin/keys → HTTP 401" 401 "$(http_code $API/api/admin/keys)"

R=$(curl -s $API/api/users -H "Authorization: Bearer $EMP_TOKEN")
check "V4  EMPLOYEE GET /api/users（用户管理）→ 403" 403 "$(json_code "$R")"
R=$(curl -s "$API/api/audit-logs?page=1&size=10" -H "Authorization: Bearer $EMP_TOKEN")
check "V5  EMPLOYEE GET /api/audit-logs（翻审计账）→ 403" 403 "$(json_code "$R")"
R=$(curl -s $API/api/admin/keys -H "Authorization: Bearer $EMP_TOKEN")
check "V6  EMPLOYEE GET /api/admin/keys（看密钥）→ 403" 403 "$(json_code "$R")"
R=$(curl -s -X POST $API/api/admin/crypto/backfill -H "Authorization: Bearer $EMP_TOKEN")
check "V7  EMPLOYEE POST /api/admin/crypto/backfill（数据级运维）→ 403" 403 "$(json_code "$R")"
R=$(curl -s $API/api/employees/1/sensitive -H "Authorization: Bearer $EMP_TOKEN")
check "V8  EMPLOYEE GET /api/employees/1/sensitive（看明文）→ 403" 403 "$(json_code "$R")"
R=$(curl -s -G $API/api/employees/search --data-urlencode "idCard=110101199003071234" \
  -H "Authorization: Bearer $EMP_TOKEN")
check "V9  EMPLOYEE GET /api/employees/search（按身份证精确查）→ 403" 403 "$(json_code "$R")"
R=$(curl -s $API/api/roles -H "Authorization: Bearer $EMP_TOKEN")
check "V10 EMPLOYEE GET /api/roles（角色表）→ 403" 403 "$(json_code "$R")"
R=$(curl -s -X POST $API/api/employees -H "Authorization: Bearer $EMP_TOKEN" \
  -H "Content-Type: application/json" -d '{"empNo":"SECV01","name":"x","deptId":1}')
check "V11 EMPLOYEE POST /api/employees（写操作）→ 403" 403 "$(json_code "$R")"
R=$(curl -s -X DELETE $API/api/depts/1 -H "Authorization: Bearer $EMP_TOKEN")
check "V12 EMPLOYEE DELETE /api/depts/1（删部门）→ 403" 403 "$(json_code "$R")"
# 对照组：矩阵不能只有"禁止"格，否则分不清"权限收紧"和"接口全挂"
R=$(curl -s "$API/api/employees?page=1&size=5" -H "Authorization: Bearer $EMP_TOKEN")
check "V13 对照组 EMPLOYEE GET /api/employees（只读允许）→ 200" 200 "$(json_code "$R")"
R=$(curl -s $API/api/depts -H "Authorization: Bearer $EMP_TOKEN")
check "V14 对照组 EMPLOYEE GET /api/depts（只读允许）→ 200" 200 "$(json_code "$R")"

echo "===== H. 水平越权与数据边界 ====="
R=$(TOKEN=$EMP_TOKEN put_json $API/api/employees/1 '{"empNo":"E001","name":"改别人档案","deptId":1}')
check "H1  EMPLOYEE 改他人员工档案（水平越权）→ 403" 403 "$(json_code "$R")"
R=$(curl -s -X PUT $API/api/users/1/status -H "Authorization: Bearer $EMP_TOKEN" \
  -H "Content-Type: application/json" -d '{"status":0}')
check "H2  EMPLOYEE 禁用他人账号（水平越权）→ 403" 403 "$(json_code "$R")"
R=$(curl -s -X PUT $API/api/users/2/roles -H "Authorization: Bearer $EMP_TOKEN" \
  -H "Content-Type: application/json" -d '{"roleIds":[1]}')
check "H3  EMPLOYEE 给自己提权 ADMIN（自己改自己角色）→ 403" 403 "$(json_code "$R")"

# 已知边界（不是漏洞，是设计取舍，论文/答辩要主动讲）：
# 本系统权限模型是"角色级"的，**没有数据归属/部门数据范围隔离** ——
# EMPLOYEE 可以读到全部部门的员工列表（只读、字段已脱敏）。
# 把它写成断言，是为了让"边界在哪"可复现、可讨论，而不是靠口头保证。
R=$(curl -s "$API/api/employees?page=1&size=50" -H "Authorization: Bearer $EMP_TOKEN")
TOTAL=$(json_field "$R" "d['data']['total']")
if [ "${TOTAL:-0}" -ge 3 ] 2>/dev/null; then
  echo "PASS | H4  已知边界：EMPLOYEE 可见全量员工列表（无部门数据隔离，total=$TOTAL，字段已脱敏）"; PASS=$((PASS+1))
else
  echo "FAIL | H4  期望 EMPLOYEE 可见全量列表 total>=3 实际 total=${TOTAL:-空}"; FAIL=$((FAIL+1))
fi

echo "===== I. 注入（SQL 结构 / LIKE 通配符 / 明文入参）====="
inject_total() { # $1=用例名 $2=keyword 字面量 —— 统一断言"200 且命中 0 条"（既没报错，也没被通配符/注入放大成全表）
  local R TOTAL
  R=$(curl -s -G $API/api/employees --data-urlencode "keyword=$2" -H "Authorization: Bearer $ADMIN_TOKEN")
  TOTAL=$(json_field "$R" "d['data']['total']")
  check "$1" "200|0" "$(json_code "$R")|${TOTAL:-空}"
}
inject_total "I1  keyword=' OR '1'='1（经典注入）→ 不返回全表" "' OR '1'='1"
inject_total "I2  keyword=' OR 1=1 -- （注释截断）→ 不返回全表" "' OR 1=1 -- "
inject_total "I3  keyword 单个单引号（破坏 SQL 字面量）→ 200 且 0 条" "'"
# I4/I5 是本周新加固点：% 和 _ 是 LIKE 通配符，参数化拦不住；
# 加固前 keyword=% 会 LIKE '%%%' 命中全表（数据枚举 + 索引失效），加固后按字面量匹配
inject_total "I4  keyword=%（LIKE 通配符）→ 0 条（第 8 周加固点）" "%"
inject_total "I5  keyword=_（LIKE 单字符通配符）→ 0 条（第 8 周加固点）" "_"

R=$(curl -s -G $API/api/employees/search --data-urlencode "idCard=' OR '1'='1" \
  -H "Authorization: Bearer $ADMIN_TOKEN")
LEN=$(json_field "$R" "len(d['data'])")
check "I6  search?idCard=' OR '1'='1 → 200 且命中 0 条（走哈希等值匹配）" "200|0" "$(json_code "$R")|${LEN:-空}"

R=$(curl -s -G $API/api/users --data-urlencode "keyword=%" -H "Authorization: Bearer $ADMIN_TOKEN")
UTOTAL=$(json_field "$R" "d['data']['total']")
check "I7  /api/users?keyword=%（用户列表同样转义）→ 0 条" "200|0" "$(json_code "$R")|${UTOTAL:-空}"

R=$(curl -s -X POST $API/api/auth/login -H "Content-Type: application/json" \
  -d '{"username":"'"'"' OR '"'"'1'"'"'='"'"'1","password":"x"}')
check "I8  登录 username 传注入串 → 400（业务错误，不是 500）" 400 "$(json_code "$R")"

echo "===== X. XSS（存储型：用户写入 + 列表渲染）====="
XSS_NAME='<script>alert(1)</script>'
XSS_DEPT='<img src=x onerror=alert(1)>SECXSSDEPT'
R=$(curl -s -X POST $API/api/employees -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"empNo":"SECX01","name":"<script>alert(1)</script>","deptId":1,"entryDate":"2025-01-01"}')
check_in "X1  新增员工姓名含 <script> → 200/409（能被写入：靠输出编码而不是过滤输入）" "200 409" "$(json_code "$R")"
R=$(curl -s -G $API/api/employees --data-urlencode "keyword=$XSS_NAME" -H "Authorization: Bearer $ADMIN_TOKEN")
NAME=$(json_field "$R" "d['data']['records'][0]['name'] if d['data']['records'] else ''")
check "X2  列表接口原样返回该姓名（未被\"净化\"改坏数据，转义交给渲染层）" "$XSS_NAME" "$NAME"
CT=$(curl -s -D - -o /dev/null "$API/api/employees?page=1&size=1" -H "Authorization: Bearer $ADMIN_TOKEN" \
     | grep -i "^content-type:" | tr -d '\r' | sed "s/^[^:]*: *//I")
case "$CT" in application/json*) echo "PASS | X3  响应 Content-Type=application/json（浏览器不会把内容当 HTML 执行）"; PASS=$((PASS+1));;
  *) echo "FAIL | X3  期望 application/json* 实际 $CT"; FAIL=$((FAIL+1));; esac
R=$(curl -s -X POST $API/api/depts -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" -d '{"deptName":"<img src=x onerror=alert(1)>SECXSSDEPT","sort":99}')
check_in "X4  新增部门名含 <img onerror=...> → 200/409" "200 409" "$(json_code "$R")"
R=$(curl -s $API/api/depts -H "Authorization: Bearer $ADMIN_TOKEN")
# 断言 >=1 而不是 ==1：部门名没有唯一约束，脚本重跑会留下同名的探针部门（这是数据事实，不是缺陷）
HIT=$(json_field "$R" "sum(1 for x in d['data'] if x['deptName']=='<img src=x onerror=alert(1)>SECXSSDEPT')")
if [ "${HIT:-0}" -ge 1 ] 2>/dev/null; then
  echo "PASS | X5  部门列表原样返回该名称（$HIT 条；渲染层负责转义）"; PASS=$((PASS+1))
else echo "FAIL | X5  期望部门列表里能找到该探针名称 实际 0 条"; FAIL=$((FAIL+1)); fi
# 前端的转义是"默认插值"给的；一旦出现 v-html 就等于自行绕过转义 —— 用静态断言把它钉住
VHTML=$(grep -rn "v-html" "$ROOT/hr-ui/src" 2>/dev/null | wc -l | tr -d ' ')
check "X6  前端源码不使用 v-html（0 处，渲染走 Vue 插值默认转义）" "0" "$VHTML"

echo "===== S. 安全响应头与 CSRF 适用性 ====="
check "S1  X-Content-Type-Options=nosniff" "nosniff" \
  "$(header_of X-Content-Type-Options $API/api/auth/me)"
check "S2  X-Frame-Options=DENY（防点击劫持）" "DENY" \
  "$(header_of X-Frame-Options $API/api/auth/me)"
check "S3  Referrer-Policy=no-referrer" "no-referrer" \
  "$(header_of Referrer-Policy $API/api/auth/me)"
check "S4  Content-Security-Policy=default-src 'none'（本服务只产出 JSON）" \
  "default-src 'none'; frame-ancestors 'none'; base-uri 'none'" \
  "$(header_of Content-Security-Policy $API/api/auth/me)"
check "S5  带 X-Forwarded-Proto: https → 下发 HSTS" "1" \
  "$(header_count $API/api/auth/me 'X-Forwarded-Proto: https')"
check "S6  纯 http 请求不下发 HSTS（HSTS 只在安全上下文里才有意义）" "0" \
  "$(header_count $API/api/auth/me)"

# CSRF 为什么不需要 token：鉴权只认 Authorization 头，浏览器不会自动附加它。
# 用"只带 Cookie 的请求拿不到认证"来证明这个前提，比"顺手加个 CSRF token"更说明问题。
check "S7  只带 Cookie（无 Authorization）读接口 → 401（Cookie 不构成认证）" 401 \
  "$(http_code $API/api/auth/me -H "Cookie: JSESSIONID=forged; token=$ADMIN_TOKEN")"
check "S8  只带 Cookie 的写请求 → 401（攻击者站点无法让浏览器替我发带 token 的请求）" 401 \
  "$(http_code -X POST $API/api/employees -H "Cookie: token=$ADMIN_TOKEN" \
     -H 'Content-Type: application/json' -d '{"empNo":"SECS01","name":"csrf","deptId":1}')"

echo "===== B. 登录防护（爆破/撞库）====="
PROBE=sec_bruteforce_probe
$REDIS_CLI del "login:fail:user:$PROBE" >/dev/null 2>&1
CODES=""
for i in 1 2 3 4 5; do
  R=$(curl -s -X POST $API/api/auth/login -H "Content-Type: application/json" \
    -d "{\"username\":\"$PROBE\",\"password\":\"wrongpass$i\"}")
  CODES="$CODES$(json_code "$R") "
done
check "B1  连续 5 次错误口令 → 每次都 400（账号不存在与密码错误同一提示，不暴露注册状态）" \
  "400 400 400 400 400 " "$CODES"
R=$(curl -s -X POST $API/api/auth/login -H "Content-Type: application/json" \
  -d "{\"username\":\"$PROBE\",\"password\":\"wrongpass6\"}")
check "B2  第 6 次 → 429 触发锁定（阈值 5 次）" 429 "$(json_code "$R")"
check "B3  账号维度计数封顶在 5（被拒的请求不再累加）" 5 \
  "$($REDIS_CLI get "login:fail:user:$PROBE" | tr -d '\r')"
TTL=$($REDIS_CLI ttl "login:fail:user:$PROBE" | tr -d '\r')
if [ "${TTL:-0}" -gt 0 ] 2>/dev/null && [ "${TTL:-0}" -le 600 ] 2>/dev/null; then
  echo "PASS | B4  计数带 10 分钟 TTL（实际 ${TTL}s，到点自动解锁，不需要清理任务）"; PASS=$((PASS+1))
else echo "FAIL | B4  期望 TTL∈(0,600] 实际 ${TTL:-空}"; FAIL=$((FAIL+1)); fi
IPKEYS=$($REDIS_CLI keys "login:fail:ip:*" | tr -d '\r' | grep -c . )
if [ "${IPKEYS:-0}" -ge 1 ] 2>/dev/null; then
  echo "PASS | B5  IP 维度同步计数（撞库维度；阈值 20 更宽，避免\"锁别人账号\"的 DoS 反噬）"; PASS=$((PASS+1))
else echo "FAIL | B5  期望存在 login:fail:ip:* 计数"; FAIL=$((FAIL+1)); fi
R=$(curl -s -X POST $API/api/auth/login -H "Content-Type: application/json" --data-binary @login-admin.json)
check "B6  账号维度锁定不影响其它账号（ADMIN 仍可登录）" 200 "$(json_code "$R")"

# B7~B10 是注册入口的口令强度策略（只约束注册，登录不受影响）
reg() { post_anon_json "$API/api/auth/register" "$1"; }
R=$(reg '{"username":"sec_weak_pw","password":"123456","nickname":"弱口令探针"}')
check "B7  注册纯数字弱口令 123456 → 400" 400 "$(json_code "$R")"
R=$(reg '{"username":"sec_alpha_pw","password":"abcdefgh","nickname":"纯字母探针"}')
check "B8  注册纯字母口令 abcdefgh → 400" 400 "$(json_code "$R")"
WEAKROW=$($MYSQL "SELECT COUNT(*) FROM sys_user WHERE username='sec_alpha_pw'" | tail -n +2 | tr -d '\r' | head -1)
check "B9  被拒的弱口令请求没有落库（校验在写入之前）" 0 "$WEAKROW"
R=$(reg '{"username":"sec_probe_user","password":"Hr@123456","nickname":"合规口令探针"}')
check_in "B10 注册合规口令（8 位含字母数字）→ 200/409" "200 409" "$(json_code "$R")"

# B11 审计：登录失败必须留痕（而不是只留一个 Redis 计数）—— 异步落库，轮询等
$PY - <<'PYEOF' > /tmp/sec_audit_rows.txt
import os, subprocess, time
cmd = os.environ['MYSQL_CMD'].split()
sql = ("SELECT COUNT(*) FROM sys_audit_log WHERE operation='用户登录' AND result='FAILURE' "
       "AND detail LIKE '%sec_bruteforce_probe%'")
deadline, got = time.time() + 10, '0'
while time.time() < deadline:
    out = subprocess.run(cmd + [sql], capture_output=True, text=True).stdout.strip().splitlines()
    got = out[-1].strip() if out else '0'
    if got.isdigit() and int(got) >= 1:
        break
    time.sleep(0.4)
print(got)
PYEOF
FAILROWS=$(tr -d '\r' < /tmp/sec_audit_rows.txt | tail -1)
if [ "${FAILROWS:-0}" -ge 1 ] 2>/dev/null; then
  echo "PASS | B11 登录失败进审计（operation=用户登录 result=FAILURE 且记下被试探的账号，$FAILROWS 条）"; PASS=$((PASS+1))
else echo "FAIL | B11 期望审计表中有登录失败记录 实际 ${FAILROWS:-空}"; FAIL=$((FAIL+1)); fi

# 收尾：清掉本次制造的失败计数（同时演示"运维可手动解锁"，以及不污染后续脚本）
$REDIS_CLI keys "login:fail:*" 2>/dev/null | tr -d '\r' | while read -r k; do
  [ -n "$k" ] && $REDIS_CLI del "$k" >/dev/null 2>&1
done
R=$(curl -s -X POST $API/api/auth/login -H "Content-Type: application/json" \
  -d "{\"username\":\"$PROBE\",\"password\":\"wrongpass7\"}")
check "B12 清理计数后同一账号恢复为 400（锁定可解除，不是永久封号）" 400 "$(json_code "$R")"

echo
echo "===== 结果：PASS=$PASS FAIL=$FAIL ====="
