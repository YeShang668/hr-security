package com.hrsecurity.common;

/**
 * LIKE 查询关键字的通配符转义。
 *
 * 为什么参数化（#{}）之后还需要这一步（面试点）：
 * MyBatis 的 {@code like} 把值作为**参数**绑定，SQL 结构不会被改写，所以它天然防"注入"；
 * 但 {@code %} 和 {@code _} 在 LIKE 语法里是通配符，它们是**值的一部分**，参数化拦不住。
 * 于是 keyword=% 会变成 LIKE '%%%' 命中全表——不是注入漏洞，却是一个真实的
 * **数据枚举/资源消耗**入口（攻击者用一个字符就能把整表拉出来，还能让 LIKE 无法走索引）。
 *
 * 处理办法是"转义 + 依赖 MySQL 的默认转义符"：
 * MySQL 的 LIKE 默认转义字符就是反斜杠，所以把 \ % _ 前面各加一个反斜杠即可，
 * 不需要额外拼 ESCAPE 子句（也就不会去动 SQL 结构）。
 *
 * 注意：这里**只做转义，不做输入过滤**——用户想搜带百分号的字符串是合法需求，
 * 我们要保证的是"他输入的就是他搜索的字面量"。
 */
public final class LikeUtils {

    private static final char ESCAPE = '\\';

    private LikeUtils() {
    }

    /**
     * 转义 LIKE 通配符，使入参只按字面量匹配。
     * 反斜杠必须最先替换，否则会把后加的转义反斜杠再转一遍。
     */
    public static String escape(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return keyword;
        }
        StringBuilder sb = new StringBuilder(keyword.length() + 8);
        for (int i = 0; i < keyword.length(); i++) {
            char c = keyword.charAt(i);
            if (c == ESCAPE || c == '%' || c == '_') {
                sb.append(ESCAPE);
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
