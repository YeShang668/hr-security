#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
hr-security 压测脚本（第 8 周，任务 D）

为什么用 Python 标准库而不是 k6/JMeter（写在最前面，免得被问"为什么不用专业工具"）：
  k6 是单文件 exe、确实更适合做压测（脚本化场景 + 丰富的指标输出），但本机与 CI
  runner 都没有预装它，引入一个外部二进制会让"能不能复现"变成环境问题；
  本脚本用标准库线程 + 持久连接（http.client）已经能给出可信的 QPS/分位延迟，
  而且能在**同一个进程里**顺手做"并发下审计不丢"的一致性校验（拿 MySQL 行数与请求数比对）——
  这一点 k6 反而要靠外部脚本配合。等要做持续压测基线时再把 k6 补上（写进 docs/pressure-test.md）。

三类场景（对应三条不同开销路径）：
  login      登录：BCrypt 校验（故意慢，抗爆破）+ 查库 + Redis 写会话
  list       员工列表：走脱敏列，无解密开销（对照组）
  sensitive  查看敏感明文：解密 4 个字段 + 每次调用产生一条**异步**审计

用法（默认 8 并发 / 每场景 200 次）：
  python test-payloads/pressure-test.py --scenario all --concurrency 8 --requests 200
  MYSQL_CMD="mysql --default-character-set=utf8mb4 -uroot hr_security -e" \
    python test-payloads/pressure-test.py --scenario sensitive --check-audit
"""
import argparse
import http.client
import json
import os
import queue
import re
import statistics
import subprocess
import sys
import threading
import time

HOST = "localhost"
PORT = 8080
TIMEOUT = 15


def parse_args():
    p = argparse.ArgumentParser(description="hr-security 压测（标准库实现）")
    p.add_argument("--base", default=os.environ.get("API_BASE", f"http://{HOST}:{PORT}"),
                   help="接口地址，默认 http://localhost:8080")
    p.add_argument("--scenario", default="all", choices=["all", "login", "list", "sensitive"])
    p.add_argument("--concurrency", type=int, default=8, help="并发线程数")
    p.add_argument("--requests", type=int, default=200, help="每场景总请求数")
    p.add_argument("--admin-user", default="admin")
    p.add_argument("--admin-pass", default="Hr@123456")
    p.add_argument("--emp-id", type=int, default=None, help="sensitive 场景用的员工 id（默认取第一个有敏感数据的）")
    p.add_argument("--check-audit", action="store_true",
                   help="sensitive 场景结束后核对审计行数 >= 请求数（验证并发下审计不丢）")
    p.add_argument("--json", default=None, help="把汇总结果写到该 JSON 文件（便于进文档）")
    return p.parse_args()


def split_base(base):
    scheme, rest = base.split("://", 1)
    host_port = rest.split("/", 1)[0]
    path_prefix = "/" + rest.split("/", 1)[1] if "/" in rest else ""
    host, _, port = host_port.partition(":")
    return scheme, host or "localhost", int(port or (443 if scheme == "https" else 80)), path_prefix.rstrip("/")


SCHEME, HOST, PORT, PREFIX = "http", "localhost", 8080, ""


def request(method, path, body=None, token=None):
    """单次请求（用持久连接复用 TCP，避免把"建连开销"混进被测接口的耗时里）。"""
    payload = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = "Bearer " + token
    last_err = None
    for _ in range(2):          # 连接可能被服务端关掉（keep-alive 超时），重连一次
        conn = http.client.HTTPConnection(HOST, PORT, timeout=TIMEOUT)
        try:
            conn.request(method, PREFIX + path, body=payload, headers=headers)
            resp = conn.getresponse()
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, raw
        except Exception as e:   # noqa: BLE001 —— 压测要的是"错误率"，不区分异常类型
            last_err = e
        finally:
            conn.close()
    return 0, f'{{"code":0,"message":"{last_err}"}}'


def body_code(raw):
    try:
        return json.loads(raw).get("code")
    except Exception:  # noqa: BLE001
        return None


def login(user, password):
    st, raw = request("POST", "/api/auth/login", {"username": user, "password": password})
    if st == 200 and body_code(raw) == 200:
        return json.loads(raw)["data"]["token"]
    raise SystemExit(f"预检失败：无法登录（http={st} body={raw[:200]}）。"
                     f"请确认应用已启动、口令正确（演示口令已改为 Hr@123456）")


def find_emp_id(token):
    """找一个"确实有敏感数据"的员工（列表出参里 phone 是脱敏值，非 null 即代表密文列有值）。
    注意：种子员工 E001~E003 的敏感字段要靠历史数据迁移刷入，
    跑过 POST /api/admin/crypto/backfill 之后这里才找得到对象。"""
    st, raw = request("GET", "/api/employees?page=1&size=50", token=token)
    for rec in json.loads(raw)["data"]["records"]:
        if rec.get("phone"):
            return rec["id"]
    raise SystemExit("找不到含敏感数据的员工：请先执行一次历史数据加密迁移 "
                     "（POST /api/admin/crypto/backfill，或用 crypto-e2e-test.sh 跑到迁移那一步）")


def run_load(name, worker, total, concurrency):
    """线程池打 total 次请求，返回 (成功数, 失败数, 延迟列表秒)。"""
    tasks = queue.Queue()
    for _ in range(total):
        tasks.put(1)
    lock = threading.Lock()
    lat, ok, bad = [], 0, 0

    def loop():
        nonlocal ok, bad
        local_lat, local_ok, local_bad = [], 0, 0
        while True:
            try:
                tasks.get_nowait()
            except queue.Empty:
                break
            t0 = time.perf_counter()
            try:
                good = worker()
            except Exception:  # noqa: BLE001
                good = False
            local_lat.append(time.perf_counter() - t0)
            local_ok += 1 if good else 0
            local_bad += 0 if good else 1
        with lock:
            lat.extend(local_lat)
            ok += local_ok
            bad += local_bad

    threads = [threading.Thread(target=loop) for _ in range(concurrency)]
    t0 = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    wall = time.perf_counter() - t0
    lat.sort()

    def pct(p):
        if not lat:
            return 0.0
        idx = min(len(lat) - 1, int(round((p / 100.0) * len(lat) + 0.5)) - 1)
        return lat[max(0, idx)]

    return {
        "scenario": name,
        "requests": total,
        "concurrency": concurrency,
        "ok": ok,
        "failed": bad,
        "error_rate": round(bad / total * 100, 2),
        "wall_s": round(wall, 2),
        "qps": round(ok / wall, 1) if wall else 0.0,
        "avg_ms": round(statistics.fmean(lat) * 1000, 1) if lat else 0.0,
        "p50_ms": round(pct(50) * 1000, 1),
        "p95_ms": round(pct(95) * 1000, 1),
        "p99_ms": round(pct(99) * 1000, 1),
        "max_ms": round(lat[-1] * 1000, 1) if lat else 0.0,
    }


def sql_one(sql):
    cmd = os.environ.get("MYSQL_CMD")
    if not cmd:
        return None
    out = subprocess.run(cmd.split() + [sql], capture_output=True, text=True).stdout
    lines = [l.strip() for l in out.strip().splitlines() if l.strip()]
    return lines[-1] if lines else None


def method_time_stats(sql):
    """从审计记录的 detail 文本里解析"方法耗时"（BUG8-4）：
    sys_audit_log 目前**没有** elapsed_ms 列，方法耗时是写在 detail 里的一段文本
    （Aspect 的 buildDetail 拼的"耗时 Nms"）。想在压测里把
    "业务方法耗时（含解密）"和"端到端 HTTP 耗时"分开看，就只能从文本里抠。
    → 真要按耗时做聚合/报表，应该把它提升成独立数值列；这条写进 docs/pressure-test.md 的下一步。
    """
    cmd = os.environ.get("MYSQL_CMD")
    if not cmd:
        return None
    out = subprocess.run(cmd.split() + [sql], capture_output=True, text=True).stdout
    rows = out.strip().splitlines()[1:] if len(out.strip().splitlines()) > 1 else []
    vals = []
    for r in rows:
        m = re.search(r"耗时\s*(\d+)\s*ms", r)
        if m:
            vals.append(int(m.group(1)))
    if not vals:
        return None
    vals.sort()
    return {"samples": len(vals), "avg": round(sum(vals) / len(vals), 1),
            "p50": vals[len(vals) // 2], "p95": vals[min(len(vals) - 1, int(len(vals) * 0.95))],
            "max": vals[-1]}


def main():
    args = parse_args()
    global SCHEME, HOST, PORT, PREFIX
    SCHEME, HOST, PORT, PREFIX = split_base(args.base)

    admin_token = login(args.admin_user, args.admin_pass)
    print(f"[预检] 应用可用：{args.base}，已取得 admin token（并发 {args.concurrency}，每场景 {args.requests} 次）")

    scenarios = ["login", "list", "sensitive"] if args.scenario == "all" else [args.scenario]
    results = []
    audit_info = None
    sensitive_base = None

    for sc in scenarios:
        if sc == "login":
            worker = lambda: body_code(request("POST", "/api/auth/login",
                                               {"username": args.admin_user,
                                                "password": args.admin_pass})[1]) == 200
        elif sc == "list":
            worker = lambda: body_code(request("GET", "/api/employees?page=1&size=10",
                                              token=admin_token)[1]) == 200
        else:
            emp = args.emp_id or find_emp_id(admin_token)
            if args.check_audit:
                sensitive_base = sql_one("SELECT COUNT(*) FROM sys_audit_log "
                                         "WHERE operation='查看员工敏感信息'")
            print(f"[sensitive] 目标员工 id={emp}，请求前审计基线={sensitive_base}")
            worker = lambda: body_code(request("GET", f"/api/employees/{emp}/sensitive",
                                              token=admin_token)[1]) == 200
        r = run_load(sc, worker, args.requests, args.concurrency)
        results.append(r)
        print(f"[{sc}] QPS={r['qps']} P95={r['p95_ms']}ms P99={r['p99_ms']}ms 错误率={r['error_rate']}%")

        if sc == "sensitive" and args.check_audit:
            # 审计是异步落库：轮询等（最多 30s），不能发完就查
            want = int(sensitive_base or 0) + args.requests
            got, deadline = None, time.time() + 30
            while time.time() < deadline:
                got = sql_one("SELECT COUNT(*) FROM sys_audit_log WHERE operation='查看员工敏感信息'")
                if got is not None and int(got) >= want:
                    break
                time.sleep(0.5)
            method_stats = method_time_stats(
                "SELECT detail FROM sys_audit_log WHERE operation='查看员工敏感信息'")
            audit_info = {"baseline": sensitive_base, "expected": want, "actual": got,
                          "method_time": method_stats,
                          "missing": (want - int(got)) if got is not None else None}
            status = "不丢" if got is not None and int(got) >= want else "有丢失或未落库完成"
            print(f"[sensitive] 并发审计一致性：请求 {args.requests} 次，审计行 {sensitive_base}→{got}"
                  f"（期望 ≥{want}）→ {status}")
            if method_stats:
                print(f"[sensitive] 审计记录里的业务方法耗时（含解密、不含异步落库）："
                      f"avg={method_stats['avg']}ms p95={method_stats['p95']}ms max={method_stats['max']}ms"
                      f"（样本 {method_stats['samples']}）")

    report = {"base": args.base, "concurrency": args.concurrency, "requests_per_scenario": args.requests,
              "results": results, "audit": audit_info, "at": time.strftime("%Y-%m-%d %H:%M:%S")}
    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        print(f"[输出] 汇总已写入 {args.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
