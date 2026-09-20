package com.hrsecurity.crypto;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * MyBatis 的 TypeHandler 由 MyBatis 反射实例化（不是 Spring Bean），拿不到依赖注入，
 * 所以用"静态持有 + 首次使用时懒取 Spring 单例"的方式把 AesGcmCipher 交给它。
 *
 * 为什么不在类初始化/构造时注入：TypeHandler 可能在 SqlSessionFactory 构建阶段就被 new 出来，
 * 那时容器未必已把 AesGcmCipher 初始化完；懒取则保证第一次真正用到（查询/写入）时容器已就绪。
 */
@Component
public class CryptoHolder implements ApplicationContextAware {

    private static ApplicationContext context;

    private static volatile AesGcmCipher cipher;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        CryptoHolder.context = applicationContext;
    }

    public static AesGcmCipher cipher() {
        AesGcmCipher local = cipher;
        if (local == null) {
            synchronized (CryptoHolder.class) {
                local = cipher;
                if (local == null) {
                    if (context == null) {
                        throw new IllegalStateException("Spring 容器尚未就绪，无法取得加解密器");
                    }
                    local = context.getBean(AesGcmCipher.class);
                    cipher = local;
                }
            }
        }
        return local;
    }
}
