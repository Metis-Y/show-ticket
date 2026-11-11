package com.showticket.utils;

import com.showticket.dto.Result;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Collections;

import static com.showticket.utils.RedisConstants.RATE_LIMIT_KEY;

/**
 * 令牌桶限流切面：执行 rate_limit.lua，桶内还有令牌就放行，桶空返回「系统繁忙」。
 */
@Aspect
@Component
public class RateLimitAspect {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setLocation(new ClassPathResource("rate_limit.lua"));
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        Long result = stringRedisTemplate.execute(
                RATE_LIMIT_SCRIPT,
                Collections.singletonList(RATE_LIMIT_KEY),
                String.valueOf(rateLimit.capacity()),
                String.valueOf(rateLimit.rate()),
                String.valueOf(System.currentTimeMillis()),
                "1");
        if (result != null && result == 1L) {
            return pjp.proceed();
        }
        return Result.fail("系统繁忙，请稍后重试");
    }
}