package com.hrsecurity.security;

import com.hrsecurity.common.BusinessException;
import com.hrsecurity.common.IpUtils;
import com.hrsecurity.common.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

/**
 * 登录失败限流（防爆破/撞库）。
 *
 * 为什么放在 Redis 而不是内存 Map（面试点）：
 * ① 应用可以多实例部署，内存计数各算各的，等于阈值 × 实例数；
 * ② 进程重启计数清零，攻击者只要等到你重启就重新拿到额度；
 * ③ 顺带获得天然过期（TTL），不需要自己写清理线程。
 *
 * 两个维度分别计数，阈值刻意不同（这是一个取舍，不是随手写的数字）：
 * - **账号维度** max-attempts-per-user（默认 5）：针对"死磕一个账号"，
 *   它同时是注册接口密码强度之外的兜底——密码再弱也扛不住无限次猜。
 * - **IP 维度** max-attempts-per-ip（默认 20，明显更高）：针对"一个 IP 撞很多账号"。
 *   阈值必须比账号维度宽松，否则会引入一个**反噬**：攻击者故意用别人的用户名试错，
 *   把受害者账号锁住（拒绝服务），如果他还能靠同一个 IP 把整个网段/出口 IP 锁死，
 *   影响的就不只是一个账号了。多用户共用出口 IP（公司/学校 NAT）时尤其明显。
 *
 * 为什么"失败才计数、成功就清零"，而不是"限制请求频率"：
 * 本项目登录接口的正常调用量本来就低，限流的意义在于抬高**猜密码**的成本，
 * 不在于削峰。成功登录后清掉账号计数，能避免"正常用户白天输错 4 次、
 * 晚上再错 1 次就被锁"这种误伤。
 *
 * 已知局限（论文/面试要主动说）：
 * ① IP 来源是 X-Forwarded-For（可信代理写入），没有可信代理时会退化为对端地址，
 *   攻击者能伪造该头绕过 IP 维度——所以账号维度才是主要防线；
 * ② 锁定期是固定值而非指数退避，生产建议改为"失败次数越多、锁得越久"；
 * ③ 只覆盖登录接口，未覆盖注册/短信等其它可枚举接口。
 */
@Slf4j
@Service
public class LoginAttemptService {

    /** 账号维度计数：login:fail:user:{username} */
    private static final String USER_KEY_PREFIX = "login:fail:user:";

    /** IP 维度计数：login:fail:ip:{ip} */
    private static final String IP_KEY_PREFIX = "login:fail:ip:";

    private final StringRedisTemplate stringRedisTemplate;

    /** 单账号在锁定期窗口内允许的连续失败次数 */
    private final int maxAttemptsPerUser;

    /** 单 IP 在锁定期窗口内允许的连续失败次数（故意比账号维度宽松，见类注释） */
    private final int maxAttemptsPerIp;

    /** 计数窗口 / 锁定时长（分钟）：计数 key 的 TTL 就是这个值 */
    private final long lockMinutes;

    public LoginAttemptService(StringRedisTemplate stringRedisTemplate,
                              @Value("${security.login.max-attempts-per-user:5}") int maxAttemptsPerUser,
                              @Value("${security.login.max-attempts-per-ip:20}") int maxAttemptsPerIp,
                              @Value("${security.login.lock-minutes:10}") long lockMinutes) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.maxAttemptsPerUser = maxAttemptsPerUser;
        this.maxAttemptsPerIp = maxAttemptsPerIp;
        this.lockMinutes = lockMinutes;
    }

    /**
     * 登录前置检查：已锁定直接拒绝，不进入密码校验。
     *
     * 为什么必须先于密码校验：否则锁定期的"正确答案"仍然能登录进去，
     * 锁定就形同虚设（攻击者拿到正确密码的那一次正好能过）。
     */
    public void assertNotLocked(String username) {
        String ip = currentClientIp();
        long userFails = count(USER_KEY_PREFIX + username);
        if (userFails >= maxAttemptsPerUser) {
            log.warn("登录被拒绝：账号 {} 连续失败 {} 次，已进入 {} 分钟锁定", username, userFails, lockMinutes);
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS.getCode(),
                    "该账号连续登录失败次数过多，请 " + lockMinutes + " 分钟后再试");
        }
        long ipFails = count(IP_KEY_PREFIX + ip);
        if (ipFails >= maxAttemptsPerIp) {
            log.warn("登录被拒绝：来源 IP {} 连续失败 {} 次", ip, ipFails);
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS.getCode(),
                    "该来源登录失败次数过多，请 " + lockMinutes + " 分钟后再试");
        }
    }

    /** 一次"凭证错误"就记一笔（账号 + IP 同时计数），TTL 即锁定期 */
    public void onFailure(String username) {
        String ip = currentClientIp();
        increment(USER_KEY_PREFIX + username);
        if (StringUtils.hasText(ip)) {
            increment(IP_KEY_PREFIX + ip);
        }
    }

    /** 登录成功：清掉该账号的失败计数（IP 计数保留，让"撞库"仍然累积） */
    public void onSuccess(String username) {
        stringRedisTemplate.delete(USER_KEY_PREFIX + username);
    }

    /** 当前账号失败次数（测试与运维观测用，不参与鉴权） */
    public long userFailureCount(String username) {
        return count(USER_KEY_PREFIX + username);
    }

    private void increment(String key) {
        Long value = stringRedisTemplate.opsForValue().increment(key);
        // 第一次失败才设 TTL：窗口是"从第一次失败算起"，而不是每次失败都续期
        // （后者会让持续攻击永远不放过期，反而对正常用户更不友好）
        if (value != null && value == 1L) {
            stringRedisTemplate.expire(key, Duration.ofMinutes(lockMinutes));
        }
    }

    private long count(String key) {
        String value = stringRedisTemplate.opsForValue().get(key);
        if (!StringUtils.hasText(value)) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            // 值被外部改坏时不因为限流组件把登录整个打挂
            log.warn("登录失败计数非法（key={} value={}），按 0 处理", key, value);
            return 0;
        }
    }

    /** 与审计一致地取客户端 IP：仍然优先 X-Forwarded-For 最左地址（见 IpUtils） */
    private String currentClientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            return IpUtils.clientIp(request);
        }
        return null;
    }
}
