package com.showticket.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 基于 Redis 自增的全局唯一 ID 生成器
 *
 * 64 位 long 拆成两段：高 31 位时间戳（相对 BEGIN_TIMESTAMP 的秒数）+ 低 32 位当日序列号，
 * 所以生成的 ID 整体递增，订单按时间排序天然有序。
 */
@Component
public class RedisIdWorker {

    // 时间戳起点 2022-01-01，去掉它能让高位更短、ID 更小
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 序列号占低 32 位，所以每秒钟最多 2^32 个 ID
    private static final int COUNT_BITS = 32;


    /**
     * @param keyPrefix 业务前缀，例如 "order"，不同业务各自计数、互不影响
     */
    public long nextId(String keyPrefix) {
        // 1. 时间戳部分
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2. 序列号部分：key 里带日期，计数每天归零，避免无限增长溢出 32 位
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue()
                .increment(RedisConstants.KEY_PREFIX + "icr:" + keyPrefix + ":" + date);

        // 3. 时间戳左移 32 位再与序列号按位或，拼成一个 long
        return timestamp << COUNT_BITS | count;
    }

}
