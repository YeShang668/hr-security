package com.hrsecurity.audit;

/**
 * 审计补充说明的"线程内传递"：注解只能写静态文案，但审计往往要记下**运行时的细节**
 * （"状态改为禁用"、"migrated=3"、"新密钥 k2"）。业务方法往这里追加一句话，
 * 切面落库时取走并清空。
 *
 * 为什么要显式清理（坑）：ThreadLocal 在线程池里**不会被自动回收**，
 * 线程被复用后上一笔业务的补充说明会"串味"到下一笔审计记录里，
 * 产生一条看起来合理、实际张冠李戴的审计——比漏记更危险。
 * 因此切面在 finally 里无条件 clear()，业务侧不需要关心清理。
 *
 * 请求线程专用：异步线程里写它没有意义（切面已经取完值了），不要用。
 */
public final class AuditTrace {

    private static final ThreadLocal<String> DETAIL = new ThreadLocal<>();

    private AuditTrace() {
    }

    /** 追加一句运行时的补充说明（多次调用按分号拼接） */
    public static void append(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String current = DETAIL.get();
        DETAIL.set(current == null || current.isBlank() ? text : current + "；" + text);
    }

    /** 取出并清空（切面落库时调用） */
    public static String drain() {
        String value = DETAIL.get();
        DETAIL.remove();
        return value;
    }

    /** 无条件清理（切面 finally 兜底，防止线程复用串味；也兜住"记录失败没走到 drain"的情况） */
    public static void clear() {
        DETAIL.remove();
    }
}
