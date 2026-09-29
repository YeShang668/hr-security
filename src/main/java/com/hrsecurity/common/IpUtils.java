package com.hrsecurity.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 取客户端真实 IP。
 *
 * 为什么不能直接用 request.getRemoteAddr()（面试点，也是"代理链"的经典问题）：
 * 应用部署在 Nginx / 容器网关 / 负载均衡后面时，getRemoteAddr() 拿到的是**直接连过来的那一跳**
 * （网关自己的 IP，比如 172.x.x.x），所有人看起来都来自同一个地址，审计里毫无价值。
 * 真实客户端 IP 由代理写入 X-Forwarded-For，格式是逗号分隔的链：
 *   X-Forwarded-For: 真实客户端, 代理1, 代理2
 * 所以取**第一个**（最左）地址。
 *
 * 安全提醒：这个头是客户端可以伪造的，只有在"可信代理会覆盖它"的前提下才可信
 * （生产应在最外层 Nginx 重置该头，应用只信任内网网关）。审计里记它用于线索，不作为鉴权依据。
 */
public final class IpUtils {

    /** X-Forwarded-For 拆出来的地址是"客户端, 代理1, 代理2"，取第一个 */
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    /** Nginx 单跳代理常用头，作为 XFF 缺失时的兜底 */
    private static final String X_REAL_IP = "X-Real-IP";

    /** 字段长度上限，与 sys_audit_log.ip VARCHAR(64) 对齐 */
    private static final int MAX_LENGTH = 64;

    private IpUtils() {
    }

    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String ip = firstOf(X_FORWARDED_FOR, request.getHeader(X_FORWARDED_FOR));
        if (ip == null) {
            ip = firstOf(X_REAL_IP, request.getHeader(X_REAL_IP));
        }
        if (ip == null) {
            ip = request.getRemoteAddr();
        }
        return truncate(ip);
    }

    /** 取逗号分隔列表的第一个非空项；代理未设置该头时会填 "unknown"，同样视为无效 */
    private static String firstOf(String headerName, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String first = value.split(",")[0].trim();
        if (first.isEmpty() || "unknown".equalsIgnoreCase(first)) {
            return null;
        }
        return first;
    }

    private static String truncate(String ip) {
        if (ip == null) {
            return null;
        }
        return ip.length() <= MAX_LENGTH ? ip : ip.substring(0, MAX_LENGTH);
    }
}
