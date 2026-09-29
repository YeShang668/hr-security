#!/bin/bash
# hr-security 第 7 周密钥管理专项端到端测试（KEK/DEK 两级密钥 + 轮换 + 分批重加密 + 停用）
#
# 前置：先跑 e2e-test.sh 生成 admin_login.json/emp_login.json；依赖干净种子数据
# 环境变量覆盖（供 Docker 环境回归用，见 docker-e2e-test.sh）：
#   API_BASE=接口地址   MYSQL_CMD=数据库命令前缀   PYTHON=python 解释器
#   FAILFAST_CMD=用于验证"KEK 缺失必须启动失败"的命令（可选；本地默认自动探测 java+jar，
#                Docker 环境传 `docker compose run --rm -e AES_MASTER_KEY= app`）
#
# 重要：本脚本会**改变全局密钥状态**（轮换到 k2、停用 k1），因此必须放在整轮回归的最后执行；
#       重跑前请重置数据库（本地重灌 sql/init.sql，容器 docker compose down -v）。
# 用法：bash keyrotation-e2e-test.sh
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
PASS=0; FAIL=0

check() { if [ "$3" = "$2" ]; then echo "PASS | $1"; PASS=$((PASS+1));
  else echo "FAIL | $1 (期望=$2 实际=$3)"; FAIL=$((FAIL+1)); fi }
check_min() { if [ -n "$3" ] && [ "$3" -ge "$2" ] 2>/dev/null; then echo "PASS | $1（实测 $3）"; PASS=$((PASS+1));
  else echo "FAIL | $1 (期望>=$2 实际=$3)"; FAIL=$((FAIL+1)); fi }
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
wait_sql() { # 审计异步落库：$1=用例名 $2=期望值 $3=SQL（超时 10s：容器环境每条 SQL 要经 docker compose exec）
  local name="$1" expect="$2" sql="$3" got="" deadline=$(( $(date +%s) + 10 ))
  while [ "$(date +%s)" -le "$deadline" ]; do
    got=$($MYSQL "$sql" | tail -n +2 | tr -d '\r' | head -1)
    [ "$got" = "$expect" ] && break
    sleep 0.3
  done
  check "$name" "$expect" "$got"
}
wait_sql_ge() { # 同上，但只要求"至少 N 条"（同一动作可能被调用多次）
  local name="$1" min="$2" sql="$3" got="" deadline=$(( $(date +%s) + 10 ))
  while [ "$(date +%s)" -le "$deadline" ]; do
    got=$($MYSQL "$sql" | tail -n +2 | tr -d '\r' | head -1)
    [ -n "$got" ] && [ "$got" -ge "$min" ] 2>/dev/null && break
    sleep 0.3
  done
  check_min "$name" "$min" "$got"
}

# 四个敏感列的 k1 残留行数（含逻辑删除的离职员工：离职档案同样必须轮换干净）
K1_ROWS="SELECT COUNT(*) FROM sys_employee WHERE id_card_enc LIKE 'v1:k1:%' OR phone_enc LIKE 'v1:k1:%' OR bank_card_enc LIKE 'v1:k1:%' OR salary_enc LIKE 'v1:k1:%'"

IDCARD='500103199702023456'
PHONE='13500009999'

echo "===== K1. DEK 现状：落库的必须是密文 ====="
R=$(curl -s "$API/api/admin/keys" -H "Authorization: Bearer $ADMIN_TOKEN")
$PY - "$R" <<'EOF'
import json,sys
d=json.loads(sys.argv[1]); keys=(d.get("data") or [])
actives=[k for k in keys if k["status"]=="ACTIVE"]
first=keys[0] if keys else {}
expected_fields = {"keyId","status","dekFingerprint","createdAt","retiredAt"}
ok = (d["code"]==200 and len(keys)>=1 and len(actives)==1 and actives[0]["keyId"]=="k1"
      and first.get("status")=="ACTIVE" and first.get("dekFingerprint")
      and set(first.keys())==expected_fields)   # 出参只有这 5 个字段：没有任何密钥材料
print("PASS | K1 密钥列表：恰好一把 ACTIVE(k1)、带指纹、不含任何密钥材料" if ok else f"FAIL | K1 {d}")
sys.exit(0 if ok else 1)
EOF
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))
DEK=$($MYSQL "SELECT encrypted_dek FROM sys_data_key WHERE key_id='k1'" | tail -n +2 | tr -d '\r')
case "$DEK" in
  v1:kek1:*) echo "PASS | K2 DEK 以 KEK 信封加密后落库（v1:kek1:...）"; PASS=$((PASS+1));;
  *) echo "FAIL | K2 encrypted_dek=$DEK"; FAIL=$((FAIL+1));;
esac
$PY - "$DEK" <<'EOF'
import base64, sys
parts = sys.argv[1].split(':')
iv = base64.b64decode(parts[2]); ct = base64.b64decode(parts[3])
# GCM 密文长度 = 明文长度 + 16 字节认证标签 ⇒ 48 说明里层是一把 32 字节的 DEK，
# 而且它是"被加密的"（若存的是明文 DEK，长度会是 32 或 Base64 的 44 字符）
ok = len(parts) == 4 and parts[0] == "v1" and len(iv) == 12 and len(ct) == 48
print(f"PASS | K3 信封结构正确：IV=12B、密文=48B(=32B DEK+16B GCM tag)" if ok
      else f"FAIL | K3 结构异常 iv={len(iv)} ct={len(ct)} parts={len(parts)}")
sys.exit(0 if ok else 1)
EOF
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))
LEN=$($MYSQL "SELECT COUNT(*) FROM sys_data_key WHERE CHAR_LENGTH(encrypted_dek) BETWEEN 32 AND 44" | tail -n +2 | tr -d '\r')
check "K4 DEK 不是裸密钥串（无 32/44 字节级明文密钥材料）" 0 "$LEN"
# 开发环境 KEK 的固定前缀（docker-compose 与 application-local.yml 一致）：
# 断言 KEK 材料不落库——这是"KEK 只存在于环境变量"的可验证证据
KEKHIT=$($MYSQL "SELECT COUNT(*) FROM sys_data_key WHERE encrypted_dek LIKE '%NlxSCJlKMN9IIuBb%'" | tail -n +2 | tr -d '\r')
check "K5 KEK 材料不落库（encrypted_dek 中查不到 KEK 串）" 0 "$KEKHIT"

echo "===== K2. 准备 k1 密文并轮换 ====="
curl -s -X POST $API/api/admin/crypto/backfill -H "Authorization: Bearer $ADMIN_TOKEN" >/dev/null
E1ID=$(sql_one "SELECT id FROM sys_employee WHERE emp_no='E001'")
BEFORE_K1=$($MYSQL "$K1_ROWS" | tail -n +2 | tr -d '\r')
[ "$BEFORE_K1" -gt 0 ] && { echo "PASS | K6 轮换前存在 k1 密文（$BEFORE_K1 行，作为老数据基线）"; PASS=$((PASS+1)); } \
  || { echo "FAIL | K6 轮换前没有 k1 密文，无法验证向后兼容"; FAIL=$((FAIL+1)); }
R=$(curl -s -X POST "$API/api/admin/keys/rotate" -H "Authorization: Bearer $ADMIN_TOKEN")
$PY - "$R" <<'EOF'
import json,sys
d=json.loads(sys.argv[1]); data=d.get("data") or {}
ok = d["code"]==200 and data.get("previousKeyId")=="k1" and data.get("newKeyId")=="k2"
print("PASS | K7 轮换成功（k1 → k2）" if ok else f"FAIL | K7 {d}")
sys.exit(0 if ok else 1)
EOF
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))
# 关键向后兼容断言：老 k1 密文在新密钥上线后（k1 已 RETIRED）仍然读得出来
R=$(curl -s "$API/api/employees/$E1ID/sensitive" -H "Authorization: Bearer $ADMIN_TOKEN")
$PY - "$R" <<'EOF'
import json,sys
d=json.loads(sys.argv[1]); data=d.get("data") or {}
ok = d["code"]==200 and data.get("idCard")=="110101199003071234" and data.get("phone")=="13800000001"
print("PASS | K8 老密文（k1/RETIRED）仍能正常解密（不停机轮换的关键）" if ok else f"FAIL | K8 {d}")
sys.exit(0 if ok else 1)
EOF
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))
# 留一份"真正的 k1 密文"，K18 要用它验证"停用后的密钥拒绝解密"
E2_K1_ENC=$(sql_one "SELECT id_card_enc FROM sys_employee WHERE emp_no='E002'")
case "$E2_K1_ENC" in
  v1:k1:*) : ;;
  *) echo "警告：E002 的身份证密文不是 k1（$(echo "$E2_K1_ENC" | head -c 20)...），K18 可能退化";;
esac
# 新写入必须用新密钥：改 E001 的手机号，落库密文应变成 v1:k2:
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/employees/$E1ID" "{\"empNo\":\"E001\",\"name\":\"张三\",\"deptId\":1,\"phone\":\"$PHONE\"}")
check "K9a 轮换后更新敏感字段 200" 200 $(json_code "$R")
case "$(sql_one "SELECT phone_enc FROM sys_employee WHERE emp_no='E001'")" in
  v1:k2:*) echo "PASS | K9b 新写入用新 DEK（密文 keyId=k2）"; PASS=$((PASS+1));;
  *) echo "FAIL | K9b phone_enc=$(sql_one "SELECT phone_enc FROM sys_employee WHERE emp_no='E001'")"; FAIL=$((FAIL+1));;
esac
R=$(curl -s "$API/api/admin/keys" -H "Authorization: Bearer $ADMIN_TOKEN")
$PY - "$R" <<'EOF'
import json,sys
d=json.loads(sys.argv[1]); keys={k["keyId"]:k for k in (d.get("data") or [])}
k1, k2 = keys.get("k1", {}), keys.get("k2", {})
ok = (k1.get("status")=="RETIRED" and k1.get("retiredAt") and k2.get("status")=="ACTIVE")
print("PASS | K10 密钥状态机：k1=RETIRED(带退役时间)、k2=ACTIVE" if ok else f"FAIL | K10 {d}")
sys.exit(0 if ok else 1)
EOF
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))

echo "===== K3. 重加密（轮换收尾） ====="
# 残留基线要在**动作前**重新测一次（BUG7-3 的教训）：
# 上面的 K9a 通过实体更新改了 E001 的手机号，而实体更新是"整行回写"，
# 于是该行其余敏感列也被 TypeHandler 顺手用新密钥重加密了——
# 拿"轮换前"的基线去比会差一行。测试基线必须贴着被测动作测，不能跨改动复用。
STALE_NOW=$($MYSQL "$K1_ROWS" | tail -n +2 | tr -d '\r')
R=$(curl -s -X POST "$API/api/admin/keys/k1/disable" -H "Authorization: Bearer $ADMIN_TOKEN")
MSG=$($PY -c "import json,sys;print(json.loads(sys.argv[1])['message'])" "$R")
CODE=$(json_code "$R")
if [ "$CODE" = "400" ] && echo "$MSG" | grep -q "行密文引用"; then
  echo "PASS | K11 有残留时拒绝停用（报警点：$MSG）"; PASS=$((PASS+1));
else echo "FAIL | K11 code=$CODE msg=$MSG"; FAIL=$((FAIL+1)); fi
R=$(curl -s -X POST "$API/api/admin/keys/reencrypt" -H "Authorization: Bearer $ADMIN_TOKEN")
$PY - "$R" "$STALE_NOW" <<'EOF'
import json,sys
d=json.loads(sys.argv[1]); data=d.get("data") or {}; baseline=int(sys.argv[2])
ok = (d["code"]==200 and data.get("activeKeyId")=="k2"
      and data.get("reencrypted")==baseline     # 重加密前那几行 k1 密文恰好被重写
      and data.get("remaining")==0 and data.get("allDone") is True and data.get("batches")>=1)
print(f"PASS | K12 重加密执行完成（改写 {data.get('reencrypted')} 行 = 动作前残留基线 {baseline}）" if ok
      else f"FAIL | K12 期望 reencrypted={baseline} {d}")
sys.exit(0 if ok else 1)
EOF
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))
LEFT=$($MYSQL "$K1_ROWS" | tail -n +2 | tr -d '\r')
check "K13 全库不存在 k1 密文（含离职员工，count=0）" 0 "$LEFT"
R=$(curl -s -X POST "$API/api/admin/keys/reencrypt" -H "Authorization: Bearer $ADMIN_TOKEN")
BATCHES=$($PY -c "import json,sys;print((json.loads(sys.argv[1]).get('data') or {}).get('batches'))" "$R")
REENC=$($PY -c "import json,sys;print((json.loads(sys.argv[1]).get('data') or {}).get('reencrypted'))" "$R")
check "K14 重复执行幂等（batches=$BATCHES reencrypted=$REENC）" "0|0" "$BATCHES|$REENC"
R=$(curl -s "$API/api/employees/$E1ID/sensitive" -H "Authorization: Bearer $ADMIN_TOKEN")
VAL=$($PY -c "import json,sys;d=json.loads(sys.argv[1]).get('data') or {};print(f\"{d.get('idCard')}|{d.get('phone')}\")" "$R")
check "K15 重加密后明文不变（读得出来且值一致）" "110101199003071234|$PHONE" "$VAL"

echo "===== K4. 停用旧密钥 ====="
R=$(curl -s -X POST "$API/api/admin/keys/k1/disable" -H "Authorization: Bearer $ADMIN_TOKEN")
check "K16a 无残留时允许停用 k1" 200 $(json_code "$R")
ST=$($PY -c "import json,sys;d=json.loads(sys.argv[1]).get('data') or {};print(d.get('status'))" "$R")
FP=$($PY -c "import json,sys;d=json.loads(sys.argv[1]).get('data') or {};print(d.get('dekFingerprint'))" "$R")
check "K16b 状态置为 DISABLED 且密钥材料不再持有（指纹为 null）" "DISABLED|None" "$ST|$FP"
R=$(curl -s -X POST "$API/api/admin/keys/k1/disable" -H "Authorization: Bearer $ADMIN_TOKEN")
check "K17 重复停用被拒 400" 400 $(json_code "$R")
# 手工把一条真正的老 k1 密文写回去：模拟"以为轮换完了、其实还有老密文"的事故现场，
# 断言系统**拒绝解密并报错**，而不是静默返回乱码/明文
K2_ENC=$(sql_one "SELECT id_card_enc FROM sys_employee WHERE emp_no='E001'")
if [ -n "$E2_K1_ENC" ]; then
  $MYSQL "UPDATE sys_employee SET id_card_enc='$E2_K1_ENC' WHERE emp_no='E001'" >/dev/null
  CODE=$(json_code "$(curl -s "$API/api/employees/$E1ID/sensitive" -H "Authorization: Bearer $ADMIN_TOKEN")")
  if [ "$CODE" != "200" ]; then
    echo "PASS | K18 已停用密钥的密文被拒绝解密（code=$CODE，不静默降级）"; PASS=$((PASS+1));
  else echo "FAIL | K18 停用密钥的密文居然解出来了（DISABLED 未生效）"; FAIL=$((FAIL+1)); fi
  $MYSQL "UPDATE sys_employee SET id_card_enc='$K2_ENC' WHERE emp_no='E001'" >/dev/null
  R=$(curl -s "$API/api/employees/$E1ID/sensitive" -H "Authorization: Bearer $ADMIN_TOKEN")
  check "K19 恢复当前密钥密文后读取正常（现场已复原）" 200 $(json_code "$R")
else
  echo "FAIL | K18/K19 未能取得 k1 密文（无法验证停用后的拒绝解密）"; FAIL=$((FAIL+2))
fi

echo "===== K5. 权限与审计 ====="
R=$(curl -s "$API/api/admin/keys" -H "Authorization: Bearer $EMP_TOKEN")
check "K20 EMPLOYEE 查密钥列表 403" 403 $(json_code "$R")
R=$(TOKEN=$EMP_TOKEN post_json "$API/api/admin/keys/rotate" '')
check "K21 EMPLOYEE 触发轮换 403" 403 $(json_code "$R")
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$API/api/admin/keys")
check "K22 未登录查密钥 401" 401 "$CODE"
R=$(curl -s -X POST "$API/api/admin/keys/k1/disable" -H "Authorization: Bearer $EMP_TOKEN")
check "K23 EMPLOYEE 停用密钥 403" 403 $(json_code "$R")
wait_sql "K24 轮换动作留痕（审计 operation=轮换数据加密密钥）" "1" \
  "SELECT COUNT(*) FROM sys_audit_log WHERE operation='轮换数据加密密钥' AND target_type='KEY' AND result='SUCCESS'"
# 本脚本调用了两次重加密（K12 与 K14），所以这里是"至少 2 条"，不用等值断言
wait_sql_ge "K25 重加密动作留痕（K12/K14 各一条）" 2 \
  "SELECT COUNT(*) FROM sys_audit_log WHERE operation='存量数据重加密' AND result='SUCCESS'"
wait_sql "K26 停用动作留痕" "1" \
  "SELECT COUNT(*) FROM sys_audit_log WHERE operation='停用数据加密密钥' AND result='SUCCESS'"
DET=$($MYSQL "SELECT detail FROM sys_audit_log WHERE operation='轮换数据加密密钥' ORDER BY id DESC LIMIT 1" | tail -n +2 | tr -d '\r')
case "$DET" in
  *"k1"*"k2"*) echo "PASS | K27 审计 detail 记录新老 keyId（密钥变更史可倒查）"; PASS=$((PASS+1));;
  *) echo "FAIL | K27 detail=$DET"; FAIL=$((FAIL+1));;
esac

echo "===== K6. KEK 缺失必须启动失败（fail fast 不能退化） ====="
# 注意：JWT_SECRET 也要一起给（否则会先在 JwtUtil 上失败，测不到 KEK 这一条）——
# 这正是第一次跑这条用例时踩到的坑（BUG7-4）：只 unset AES_MASTER_KEY 时，
# 应用确实起不来，但报的是"JWT 密钥未配置"，用例就算"通过"也证明不了 KEK 的 fail fast。
if [ -z "$FAILFAST_CMD" ]; then
  JAR=$(ls "$ROOT"/target/hr-security-0.1.0.jar 2>/dev/null | head -1)
  JAVABIN=$(command -v java 2>/dev/null)
  [ -z "$JAVABIN" ] && [ -x "/e/devlop/JDK/bin/java.exe" ] && JAVABIN="/e/devlop/JDK/bin/java.exe"
  if [ -n "$JAVABIN" ] && [ -n "$JAR" ]; then
    WORK=$(mktemp -d)   # 换目录启动 = 读不到 application-local.yml，且显式 unset AES_MASTER_KEY
    FAILFAST_CMD="cd $WORK && env -u AES_MASTER_KEY JWT_SECRET='HrSec-FailFast-Probe-9f3a2b7c4d8e1f6a0b5c3d7e9f2a4b6c' '$JAVABIN' -jar '$JAR' --server.port=8099"
  fi
fi
if [ -z "$FAILFAST_CMD" ]; then
  echo "SKIP | K28 未找到 java/jar（可由 FAILFAST_CMD 指定命令），本用例不计入总数"
else
  OUT=$(mktemp)
  (eval "$FAILFAST_CMD" >"$OUT" 2>&1; echo "EXIT=$?" >>"$OUT") &
  BG=$!
  for i in $(seq 1 90); do
    grep -q "^EXIT=" "$OUT" && break
    sleep 1
  done
  if ! grep -q "^EXIT=" "$OUT"; then
    kill $BG 2>/dev/null
    echo "FAIL | K28 KEK 缺失时应用没有退出（可能带着默认密钥起来了）"; FAIL=$((FAIL+1))
  else
    EXITCODE=$(grep "^EXIT=" "$OUT" | tail -1 | cut -d= -f2)
    # 用 ASCII 的 AES_MASTER_KEY 作为关键字：Windows/Git Bash 下 JVM 输出可能是 GBK，
    # 中文关键字会因编码错位匹配不上，ASCII 一定在
    if [ "$EXITCODE" != "0" ] && grep -q "AES_MASTER_KEY" "$OUT"; then
      echo "PASS | K28 KEK 缺失时启动失败且明确提示 AES_MASTER_KEY（exit=$EXITCODE）"; PASS=$((PASS+1))
    else
      echo "FAIL | K28 exit=$EXITCODE，未在输出中找到 KEK 报错"; FAIL=$((FAIL+1))
      grep -E "IllegalStateException|Caused by" "$OUT" | head -3
    fi
  fi
  rm -f "$OUT"
fi

echo
echo "===== 结果：PASS=$PASS FAIL=$FAIL ====="
