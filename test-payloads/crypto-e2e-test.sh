#!/bin/bash
# hr-security 第 6 周加密专项端到端测试（AES-256-GCM 字段加密 + 动态脱敏 + 可检索性 + 历史数据迁移）
#
# 前置：先跑 e2e-test.sh 生成 admin_login.json/emp_login.json；依赖干净种子数据
# 环境变量覆盖（供 Docker 环境回归用，见 docker-e2e-test.sh）：
#   API_BASE=接口地址   MYSQL_CMD=数据库命令前缀   PYTHON=python 解释器
# 幂等：脚本开头会清理自己创建的测试员工（E200/E201），可重复运行
# 用法：bash crypto-e2e-test.sh
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
  else echo "FAIL | $1 (期望 code=$2 实际 code=$3)"; FAIL=$((FAIL+1)); fi }
json_code() { $PY -c "import json,sys;print(json.loads(sys.argv[1])['code'])" "$1"; }
# 取单值 SQL 结果：去掉表头行与 Windows 的 \r
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
post_json() { send_json POST "$1" "$2"; }
put_json() { send_json PUT "$1" "$2"; }
# 断言敏感字段的脱敏格式：$1=用例名 $2=json路径表达式 $3=明文 $4=脱敏规则(phone/idcard/bankcard/salary)
mask_check() {
  $PY - "$1" "$2" "$3" "$4" "${@:5}" <<'EOF'
import json, sys
name, value, plain, rule = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
api = None
if len(sys.argv) > 5:
    api = sys.argv[5]
def mask(v, rule):
    if rule == 'salary':
        return '****'
    head, tail = {'phone': (3, 4), 'idcard': (3, 4), 'bankcard': (0, 4)}[rule]
    if len(v) <= head + tail:
        return '*' * len(v)
    return v[:head] + '*' * (len(v) - head - tail) + v[len(v) - tail:]
expected = mask(plain, rule)
ok = value == expected
print(f"PASS | {name}（{expected}）" if ok else f"FAIL | {name} 期望 {expected} 实际 {value}")
sys.exit(0 if ok else 1)
EOF
}
label_ok() { if [ $? -eq 0 ]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi; }

# 测试数据（明文；断言脱敏值时用同一份，避免测试与实现各写一套规则）
IDCARD='50010319960101001X'
PHONE='13911112222'
BANKCARD='6217001234567890123'
SALARY='12345.67'
NEW_PHONE='13955556666'
# 清理上次运行残留（工号唯一，物理删除以便重复运行）
$MYSQL "DELETE FROM sys_employee WHERE emp_no IN ('E200','E201')" >/dev/null 2>&1

echo "===== C1. 写入自动加密（数据库里必须是密文） ====="
R=$(TOKEN=$ADMIN_TOKEN post_json $API/api/employees "{\"empNo\":\"E200\",\"name\":\"加密员工\",\"gender\":1,\"phone\":\"$PHONE\",\"idCard\":\"$IDCARD\",\"bankCard\":\"$BANKCARD\",\"salary\":$SALARY,\"deptId\":1,\"entryDate\":\"2025-05-01\"}")
check "C1a 新增员工(含敏感明文) 200" 200 $(json_code "$R")
EID=$($PY -c "import json,sys;d=json.loads(sys.argv[1]);print(d['data']['id'] if d.get('data') else 0)" "$R")
ENC=$($MYSQL "SELECT id_card_enc FROM sys_employee WHERE emp_no='E200'" | tail -n +2 | tr -d '\r' | head -1)
case "$ENC" in
  v1:k1:*) echo "PASS | C1b 身份证落库格式为 v1:{keyId}:{iv}:{ct} 密文"; PASS=$((PASS+1));;
  *) echo "FAIL | C1b 密文格式异常：$ENC"; FAIL=$((FAIL+1));;
esac
HIT=$($MYSQL "SELECT COUNT(*) FROM sys_employee WHERE emp_no='E200' AND (id_card_enc LIKE '%$IDCARD%' OR phone_enc LIKE '%$PHONE%' OR bank_card_enc LIKE '%$BANKCARD%' OR salary_enc LIKE '%$SALARY%')" | tail -n +2 | tr -d '\r')
check "C1c 明文不出现在任何敏感列(0 行命中)" 0 "$HIT"
HASHLEN=$($MYSQL "SELECT CHAR_LENGTH(id_card_hash) FROM sys_employee WHERE emp_no='E200'" | tail -n +2 | tr -d '\r')
check "C1d 身份证哈希列 64 位(hex)" 64 "$HASHLEN"
HASHPLAIN=$($MYSQL "SELECT COUNT(*) FROM sys_employee WHERE emp_no='E200' AND id_card_hash='$IDCARD'" | tail -n +2 | tr -d '\r')
check "C1e 哈希列不是明文本身" 0 "$HASHPLAIN"

echo "===== C2. 读取自动解密 + 列表默认脱敏 ====="
R=$(curl -s "$API/api/employees/$EID/sensitive" -H "Authorization: Bearer $ADMIN_TOKEN")
$PY - "$R" "$IDCARD" "$PHONE" "$BANKCARD" "$SALARY" <<'EOF'
import json, sys
d = json.loads(sys.argv[1]); idcard, phone, bank, salary = sys.argv[2:6]
data = d.get("data") or {}
ok = (d["code"] == 200 and data.get("idCard") == idcard and data.get("phone") == phone
      and data.get("bankCard") == bank and data.get("salary") == salary)
print("PASS | C2a 敏感接口返回明文(TypeHandler 解密往返正确)" if ok else f"FAIL | C2a {d}")
sys.exit(0 if ok else 1)
EOF
label_ok
R=$(curl -s "$API/api/employees?page=1&size=20" -H "Authorization: Bearer $ADMIN_TOKEN")
VAL=$($PY -c "import json,sys;d=json.loads(sys.argv[1]);print(next((r['phone'] for r in d['data']['records'] if r['empNo']=='E200'),''))" "$R")
mask_check "C2b ADMIN 列表同为脱敏值（最小披露）" "$VAL" "$PHONE" phone; label_ok
VAL=$($PY -c "import json,sys;d=json.loads(sys.argv[1]);print(next((r['idCard'] for r in d['data']['records'] if r['empNo']=='E200'),''))" "$R")
mask_check "C2c 列表身份证脱敏(保留前3后4)" "$VAL" "$IDCARD" idcard; label_ok
VAL=$($PY -c "import json,sys;d=json.loads(sys.argv[1]);print(next((r['bankCard'] for r in d['data']['records'] if r['empNo']=='E200'),''))" "$R")
mask_check "C2d 列表银行卡脱敏(仅后4位)" "$VAL" "$BANKCARD" bankcard; label_ok
VAL=$($PY -c "import json,sys;d=json.loads(sys.argv[1]);print(next((r['salary'] for r in d['data']['records'] if r['empNo']=='E200'),''))" "$R")
mask_check "C2e 列表工资脱敏(****)" "$VAL" "$SALARY" salary; label_ok
FLAG=$($PY -c "import json,sys;d=json.loads(sys.argv[1]);print(next((r['sensitiveVisible'] for r in d['data']['records'] if r['empNo']=='E200'),None))" "$R")
check "C2f 有权限者 sensitiveVisible=true" True "$FLAG"
R=$(curl -s "$API/api/employees?page=1&size=20" -H "Authorization: Bearer $EMP_TOKEN")
FLAG=$($PY -c "import json,sys;d=json.loads(sys.argv[1]);print(next((r['sensitiveVisible'] for r in d['data']['records'] if r['empNo']=='E200'),None))" "$R")
check "C2g 无权限者 sensitiveVisible=false" False "$FLAG"

echo "===== C3. 明文接口权限边界 ====="
R=$(curl -s "$API/api/employees/$EID/sensitive" -H "Authorization: Bearer $EMP_TOKEN")
check "C3a EMPLOYEE 取明文 403" 403 $(json_code "$R")
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$API/api/employees/$EID/sensitive")
check "C3b 未登录取明文 401" 401 "$CODE"
R=$(TOKEN=$EMP_TOKEN post_json $API/api/admin/crypto/backfill '')
check "C3c EMPLOYEE 触发加密迁移 403" 403 $(json_code "$R")
R=$(curl -s "$API/api/employees/search?idCard=$IDCARD" -H "Authorization: Bearer $EMP_TOKEN")
check "C3d EMPLOYEE 按身份证检索 403" 403 $(json_code "$R")
R=$(curl -s "$API/api/employees/99999/sensitive" -H "Authorization: Bearer $ADMIN_TOKEN")
check "C3e 员工不存在 404" 404 $(json_code "$R")

echo "===== C4. IV 随机性（同一明文两次加密结果必须不同） ====="
ENC1=$($MYSQL "SELECT id_card_enc FROM sys_employee WHERE emp_no='E200'" | tail -n +2 | tr -d '\r' | head -1)
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/employees/$EID" "{\"empNo\":\"E200\",\"name\":\"加密员工\",\"gender\":1,\"idCard\":\"$IDCARD\",\"deptId\":1,\"entryDate\":\"2025-05-01\"}")
check "C4a 用同一身份证再次更新 200" 200 $(json_code "$R")
ENC2=$($MYSQL "SELECT id_card_enc FROM sys_employee WHERE emp_no='E200'" | tail -n +2 | tr -d '\r' | head -1)
if [ -n "$ENC1" ] && [ "$ENC1" != "$ENC2" ]; then echo "PASS | C4b 两次密文不同(IV 随机，非确定性加密)"; PASS=$((PASS+1));
  else echo "FAIL | C4b 两次密文相同：IV 可能被复用！"; FAIL=$((FAIL+1)); fi
R=$(curl -s "$API/api/employees/$EID/sensitive" -H "Authorization: Bearer $ADMIN_TOKEN")
VAL=$($PY -c "import json,sys;d=json.loads(sys.argv[1]);print((d.get('data') or {}).get('idCard',''))" "$R")
check "C4c 重新加密后仍能解出同一明文" "$IDCARD" "$VAL"

echo "===== C5. 完整性保护（密文被篡改必须解不开） ====="
ENC=$($MYSQL "SELECT id_card_enc FROM sys_employee WHERE emp_no='E200'" | tail -n +2 | tr -d '\r' | head -1)
TAMPERED=$($PY - "$ENC" <<'EOF'
import sys
parts = sys.argv[1].split(':')
ct = parts[3]
# 改掉密文段的第一个字符（保证长度不变、Base64 仍合法，只能被认证标签抓住）
first = ct[0]
flipped = ('B' if first == 'A' else 'A') + ct[1:]
print(':'.join(parts[:3] + [flipped]))
EOF
)
$MYSQL "UPDATE sys_employee SET id_card_enc='$TAMPERED' WHERE emp_no='E200'" >/dev/null
R=$(curl -s "$API/api/employees/$EID/sensitive" -H "Authorization: Bearer $ADMIN_TOKEN")
CODE=$(json_code "$R")
if [ "$CODE" != "200" ]; then echo "PASS | C5a 篡改密文后解密被拒(而非返回乱码)，code=$CODE"; PASS=$((PASS+1));
  else echo "FAIL | C5a 篡改后仍返回 200：GCM 认证标签未生效"; FAIL=$((FAIL+1)); fi
# 设计取舍：密文损坏时"读"整体失败（fail loud），系统不会静默降级返回乱码或 null，
# 代价是该行在被修复前无法通过接口读到——修复需运维先清掉不可读的密文再重新录入
$MYSQL "UPDATE sys_employee SET id_card_enc=NULL WHERE emp_no='E200'" >/dev/null
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/employees/$EID" "{\"empNo\":\"E200\",\"name\":\"加密员工\",\"gender\":1,\"idCard\":\"$IDCARD\",\"deptId\":1,\"entryDate\":\"2025-05-01\"}")
check "C5b 清掉损坏密文后重新录入 200" 200 $(json_code "$R")
R=$(curl -s "$API/api/employees/$EID/sensitive" -H "Authorization: Bearer $ADMIN_TOKEN")
VAL=$($PY -c "import json,sys;d=json.loads(sys.argv[1]);print((d.get('data') or {}).get('idCard',''))" "$R")
check "C5c 重新录入后可正常解密" "$IDCARD" "$VAL"

echo "===== C6. 可检索性（HMAC 盲索引） ====="
R=$(curl -s "$API/api/employees/search?idCard=$IDCARD" -H "Authorization: Bearer $ADMIN_TOKEN")
$PY - "$R" "$IDCARD" <<'EOF'
import json, sys
d = json.loads(sys.argv[1]); idcard = sys.argv[2]
recs = (d.get("data") or []) if d["code"] == 200 else []
ok = d["code"] == 200 and len(recs) == 1 and recs[0]["empNo"] == "E200" and recs[0]["idCard"] == idcard[:3] + "*" * 11 + idcard[-4:]
print("PASS | C6a 按身份证精确查命中1条(且返回脱敏值)" if ok else f"FAIL | C6a {d}")
sys.exit(0 if ok else 1)
EOF
label_ok
R=$(curl -s "$API/api/employees/search?idCard=110101199001011111" -H "Authorization: Bearer $ADMIN_TOKEN")
CNT=$($PY -c "import json,sys;d=json.loads(sys.argv[1]);print(len(d.get('data') or []))" "$R")
check "C6b 查不存在的身份证 0 条" 0 "$CNT"
R=$(TOKEN=$ADMIN_TOKEN post_json $API/api/employees "{\"empNo\":\"E201\",\"name\":\"重复证件\",\"deptId\":1,\"idCard\":\"$IDCARD\"}")
check "C6c 身份证重复(哈希唯一校验) 409" 409 $(json_code "$R")
LIKECNT=$($MYSQL "SELECT COUNT(*) FROM sys_employee WHERE id_card_enc LIKE '%$IDCARD%'" | tail -n +2 | tr -d '\r')
check "C6d 密文列 like 明文查不到(证明加密后不可模糊查，0 行)" 0 "$LIKECNT"

echo "===== C7. 历史数据加密迁移（幂等刷数） ====="
# 先把种子员工(E001~E003)的密文列清空，模拟"尚未迁移"的旧系统状态
$MYSQL "UPDATE sys_employee SET id_card_enc=NULL, phone_enc=NULL, bank_card_enc=NULL, salary_enc=NULL, id_card_hash=NULL WHERE emp_no IN ('E001','E002','E003')" >/dev/null
R=$(TOKEN=$ADMIN_TOKEN post_json $API/api/admin/crypto/backfill '')
$PY - "$R" <<'EOF'
import json, sys
d = json.loads(sys.argv[1]); data = d.get("data") or {}
ok = d["code"] == 200 and data.get("scanned") == 3 and data.get("migrated") == 3 and data.get("unmatched") == 0
print("PASS | C7a 首次迁移 migrated=3" if ok else f"FAIL | C7a {d}")
sys.exit(0 if ok else 1)
EOF
label_ok
R=$(TOKEN=$ADMIN_TOKEN post_json $API/api/admin/crypto/backfill '')
$PY - "$R" <<'EOF'
import json, sys
d = json.loads(sys.argv[1]); data = d.get("data") or {}
ok = d["code"] == 200 and data.get("migrated") == 0 and data.get("skipped") == 3
print("PASS | C7b 重复执行 migrated=0 skipped=3（幂等，不重复加密）" if ok else f"FAIL | C7b {d}")
sys.exit(0 if ok else 1)
EOF
label_ok
E1ID=$($MYSQL "SELECT id FROM sys_employee WHERE emp_no='E001'" | tail -n +2 | tr -d '\r')
R=$(curl -s "$API/api/employees/$E1ID/sensitive" -H "Authorization: Bearer $ADMIN_TOKEN")
$PY - "$R" <<'EOF'
import json, sys
d = json.loads(sys.argv[1]); data = d.get("data") or {}
ok = (d["code"] == 200 and data.get("idCard") == "110101199003071234"
      and data.get("phone") == "13800000001" and data.get("salary") == "18000.00")
print("PASS | C7c 迁移后可解密为旧系统明文" if ok else f"FAIL | C7c {d}")
sys.exit(0 if ok else 1)
EOF
label_ok
E1ENC=$($MYSQL "SELECT phone_enc FROM sys_employee WHERE emp_no='E001'" | tail -n +2 | tr -d '\r')
case "$E1ENC" in
  v1:k1:*) echo "PASS | C7d 迁移后库里仍是密文（明文源没有直接搬过来）"; PASS=$((PASS+1));;
  *) echo "FAIL | C7d $E1ENC"; FAIL=$((FAIL+1));;
esac

echo "===== C8. 更新语义（防脱敏值回填，脏数据在入口被拦） ====="
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/employees/$EID" '{"empNo":"E200","name":"只改姓名","gender":2,"deptId":1}')
check "C8a 敏感字段留空=不修改 200" 200 $(json_code "$R")
R=$(curl -s "$API/api/employees/$EID/sensitive" -H "Authorization: Bearer $ADMIN_TOKEN")
VAL=$($PY -c "import json,sys;d=json.loads(sys.argv[1]);print((d.get('data') or {}).get('phone',''))" "$R")
check "C8b 留空后原手机号未被清空/覆盖" "$PHONE" "$VAL"
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/employees/$EID" "{\"empNo\":\"E200\",\"name\":\"只改姓名\",\"deptId\":1,\"phone\":\"139****2222\"}")
check "C8c 掩码值当明文提交 400（后端兜底拦住）" 400 $(json_code "$R")
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/employees/$EID" "{\"empNo\":\"E200\",\"name\":\"只改姓名\",\"deptId\":1,\"idCard\":\"12345\"}")
check "C8d 身份证格式非法 400" 400 $(json_code "$R")
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/employees/$EID" "{\"empNo\":\"E200\",\"name\":\"只改姓名\",\"deptId\":1,\"salary\":-1}")
check "C8e 工资负数 400" 400 $(json_code "$R")
R=$(TOKEN=$ADMIN_TOKEN put_json "$API/api/employees/$EID" "{\"empNo\":\"E200\",\"name\":\"只改姓名\",\"deptId\":1,\"phone\":\"$NEW_PHONE\"}")
check "C8f 提供新手机号则正常更新 200" 200 $(json_code "$R")
R=$(curl -s "$API/api/employees/$EID/sensitive" -H "Authorization: Bearer $ADMIN_TOKEN")
VAL=$($PY -c "import json,sys;d=json.loads(sys.argv[1]);print((d.get('data') or {}).get('phone',''))" "$R")
check "C8g 新手机号解密正确" "$NEW_PHONE" "$VAL"

echo "===== C9. 空敏感字段不产生假数据 ====="
R=$(TOKEN=$ADMIN_TOKEN post_json $API/api/employees '{"empNo":"E201","name":"无敏感字段","gender":1,"deptId":1}')
check "C9a 不带敏感字段新增 200" 200 $(json_code "$R")
VALS=$($PY -c "import json,sys;d=json.loads(sys.argv[1]);r=(d.get('data') or {});print(f\"{r.get('phone')}|{r.get('idCard')}|{r.get('salary')}\")" "$R")
check "C9b 空值原样为 null（不是 ***）" "None|None|None" "$VALS"

echo
echo "===== 结果：PASS=$PASS FAIL=$FAIL ====="
