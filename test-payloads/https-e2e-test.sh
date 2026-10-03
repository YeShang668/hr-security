#!/bin/bash
# hr-security 第 8 周 HTTPS 入口专项测试（Nginx 终止 TLS + 静态托管 + 同源反代）
#
# 只覆盖"这一层引入的新面"：跳转 / TLS 版本与证书 / HSTS / 安全头 / SPA 回退 /
# /api 反代可用性 / **X-Forwarded-For 被重置**（伪造来源 IP 必须失效）。
# 功能本身的 200+ 条用例仍由其余脚本在 http 直连端口上跑（见 docker-compose.test.yml 的说明）。
#
# 前置：docker compose -f docker-compose.yml -f docker-compose.test.yml up -d --build
#   且已生成证书（bash docker-build/nginx/gen-cert.sh）
# 环境变量：HTTPS_BASE（默认 https://localhost）  MYSQL_CMD  PYTHON
# 用法：bash https-e2e-test.sh
#
# 关于 -k（--insecure）：这里**故意**用它，理由写在 docker-compose.test.yml：
# 自签证书在 Windows(Git Bash/Schannel) 下无法通过 CURL_CA_BUNDLE 注入信任根，
# 而这套用例要验证的是"入口形态与代理语义"，不是证书链本身（生产由 Let's Encrypt 承担）。
# 证书内容（SAN/有效期）单独由 T3 用 openssl 直接检查。
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"

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
[ -z "$PY" ] && { echo "未找到可用的 python 解释器"; exit 1; }

HTTPS_BASE=${HTTPS_BASE:-https://localhost}
HTTP_BASE=${HTTP_BASE:-http://localhost}
MYSQL=${MYSQL_CMD:-/e/devlop/mysql-8.4.9-winx64/bin/mysql.exe --default-character-set=utf8mb4 -uroot hr_security -e}
ADMIN_TOKEN=$($PY -c "import json;print(json.load(open('admin_login.json'))['data']['token'])")
PASS=0; FAIL=0
check() { if [ "$3" = "$2" ]; then echo "PASS | $1"; PASS=$((PASS+1));
  else echo "FAIL | $1 (期望=$2 实际=$3)"; FAIL=$((FAIL+1)); fi }
header_of() { curl -sk -D - -o /dev/null "$2" | grep -i "^$1:" | tr -d '\r' | sed "s/^[^:]*: *//I"; }

echo "===== T. HTTPS 入口（Nginx + 自签证书）====="
# T1 80 只跳转：明文请求不得承载任何业务（否则 token 可能走明文）
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$HTTP_BASE/")
check "T1  http://localhost → 301 跳转" 301 "$CODE"
LOC=$(curl -s -D - -o /dev/null "$HTTP_BASE/" | grep -i "^location:" | tr -d '\r' | sed "s/^[^:]*: *//I")
case "$LOC" in https://*) echo "PASS | T2  跳转目标为 https（Location=$LOC）"; PASS=$((PASS+1));;
  *) echo "FAIL | T2  期望 Location 以 https:// 开头 实际 $LOC"; FAIL=$((FAIL+1));; esac

# T3 证书本身：SAN 必须含 localhost（现代客户端只看 SAN，不看 CN）
CRT="$ROOT/docker-build/nginx/certs/server.crt"
if [ -f "$CRT" ]; then
  SAN=$(openssl x509 -in "$CRT" -noout -ext subjectAltName 2>/dev/null | tr -d '\r' | tr -s ' ')
  case "$SAN" in *DNS:localhost*) echo "PASS | T3  证书 SAN 含 DNS:localhost（$SAN）"; PASS=$((PASS+1));;
    *) echo "FAIL | T3  证书 SAN 缺 localhost：$SAN"; FAIL=$((FAIL+1));; esac
else echo "FAIL | T3  证书文件不存在：$CRT（先跑 gen-cert.sh）"; FAIL=$((FAIL+1)); fi

# T4/T5 TLS 版本：1.2 能握手（拿到服务端证书），1.1 拿不到
T12=$(openssl s_client -connect localhost:443 -tls1_2 </dev/null 2>/dev/null | grep -c "BEGIN CERTIFICATE")
check "T4  TLS 1.2 可握手（收到服务端证书）" "1" "$T12"
T11=$(openssl s_client -connect localhost:443 -tls1_1 </dev/null 2>/dev/null | grep -c "BEGIN CERTIFICATE")
check "T5  TLS 1.1 无法握手（服务端只允许 1.2/1.3，连接不返回证书）" "0" "$T11"

# T6 静态托管：SPA 首页
CT=$(curl -sk -D - -o /dev/null "$HTTPS_BASE/" | grep -i "^content-type:" | tr -d '\r' | sed "s/^[^:]*: *//I")
case "$CT" in text/html*) echo "PASS | T6  https://localhost/ 返回 SPA 首页（$CT）"; PASS=$((PASS+1));;
  *) echo "FAIL | T6  期望 text/html 实际 $CT"; FAIL=$((FAIL+1));; esac
# T7 SPA 路由回退：前端路由刷新不能 404
CODE=$(curl -sk -o /dev/null -w "%{http_code}" "$HTTPS_BASE/audit-logs")
check "T7  前端路由 /audit-logs 刷新回退到 index.html（try_files）" 200 "$CODE"

# T8 HSTS 由入口层下发（应用侧只在 https 请求上才发，见 SecurityConfig）
HSTS=$(header_of Strict-Transport-Security "$HTTPS_BASE/")
case "$HSTS" in *max-age=31536000*) echo "PASS | T8  HSTS 下发（$HSTS）"; PASS=$((PASS+1));;
  *) echo "FAIL | T8  期望 HSTS 含 max-age=31536000 实际 [$HSTS]"; FAIL=$((FAIL+1));; esac
check "T9  入口层 X-Frame-Options=DENY（静态页也戴安全头）" "DENY" "$(header_of X-Frame-Options "$HTTPS_BASE/")"
CSP=$(header_of Content-Security-Policy "$HTTPS_BASE/")
case "$CSP" in *"default-src 'self'"*) echo "PASS | T10 前端 CSP 下发（含 default-src 'self'）"; PASS=$((PASS+1));;
  *) echo "FAIL | T10 期望 CSP 含 default-src 'self' 实际 [$CSP]"; FAIL=$((FAIL+1));; esac

# T11 /api 同源反代：前端不发跨域请求就能打到后端
R=$(curl -sk -X POST "$HTTPS_BASE/api/auth/login" -H "Content-Type: application/json" \
  --data-binary @login-admin.json)
check "T11 https 下 /api 反代可用（登录 200，无需 CORS）" 200 \
  "$($PY -c "import json,sys;print(json.loads(sys.argv[1])['code'])" "$R")"

# T12 核心安全语义：反代必须**重置** X-Forwarded-For，
# 否则客户端自带 XFF 就能把审计里的来源 IP 伪造成任意值（审计不可信 + 可绕过登录限流的 IP 维度）
E1ID=$($MYSQL "SELECT id FROM sys_employee WHERE emp_no='E001'" | tail -n +2 | tr -d '\r' | head -1)
if [ -n "$E1ID" ]; then
  curl -sk "$HTTPS_BASE/api/employees/$E1ID/sensitive" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "X-Forwarded-For: 203.0.113.9" >/dev/null
  sleep 1   # 审计异步落库，这里只需要"稍后读得到"，精确等待交给 audit 脚本的 wait_sql
  REC=$($MYSQL "SELECT ip FROM sys_audit_log WHERE operation='查看员工敏感信息' ORDER BY id DESC LIMIT 1" \
        | tail -n +2 | tr -d '\r' | head -1)
  if [ -n "$REC" ] && [ "$REC" != "203.0.113.9" ]; then
    echo "PASS | T12 伪造 X-Forwarded-For 被入口层重置（客户端声称 203.0.113.9，审计记录 $REC）"; PASS=$((PASS+1))
  else
    echo "FAIL | T12 伪造 XFF 竟然生效或被记空：审计记录 [$REC]（期望非 203.0.113.9 且非空）"; FAIL=$((FAIL+1))
  fi
else
  echo "FAIL | T12 找不到种子员工 E001，无法验证代理链语义"; FAIL=$((FAIL+1))
fi

echo
echo "===== 结果：PASS=$PASS FAIL=$FAIL ====="
