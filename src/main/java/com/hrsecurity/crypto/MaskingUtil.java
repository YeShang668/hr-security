package com.hrsecurity.crypto;

/**
 * 动态脱敏工具（纯函数，无状态，方便单测/回归断言）。
 *
 * 脱敏规则参考 GB/T 35273《个人信息安全规范》与常见行业做法：
 * - 身份证：保留前 3 后 4，其余掩掉（110101199003071234 → 110***********1234）
 * - 手机号：保留前 3 后 4（13800000001 → 138****0001）
 * - 银行卡：只保留后 4 位（卡号泄露风险高于手机号，前几位是发卡行标识不算敏感，但整体少露更稳）
 * - 工资：全部掩掉（数值本身就是敏感信息，掩一部分等于泄露量级）
 *
 * 两条红线：
 * 1. **脱敏必须在后端做**。前端脱敏等于没脱敏——接口里返回了明文，抓包/改前端 JS 就能看到；
 * 2. 脱敏只用于"默认展示"，不能替代权限控制：需要明文的场景必须走独立权限 + 独立接口（见
 *    EmployeeController#getSensitive），并留下审计（第 7 周 AOP）。
 */
public final class MaskingUtil {

    /** 掩码字符 */
    private static final String MASK = "*";

    private MaskingUtil() {
    }

    /** 身份证：前 3 后 4 */
    public static String maskIdCard(String value) {
        return maskMiddle(value, 3, 4);
    }

    /** 手机号：前 3 后 4 */
    public static String maskPhone(String value) {
        return maskMiddle(value, 3, 4);
    }

    /** 银行卡：仅后 4 位 */
    public static String maskBankCard(String value) {
        return maskMiddle(value, 0, 4);
    }

    /** 工资：整体掩码（保留位数都算泄露） */
    public static String maskSalary(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return MASK.repeat(4);
    }

    /**
     * 通用"保留头尾、中间打码"。
     * 长度不够（比要保留的还短）时整体打码——宁可多掩，不可漏掩。
     */
    private static String maskMiddle(String value, int keepHead, int keepTail) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        int length = value.length();
        if (length <= keepHead + keepTail) {
            return MASK.repeat(length);
        }
        return value.substring(0, keepHead)
                + MASK.repeat(length - keepHead - keepTail)
                + value.substring(length - keepTail);
    }
}
