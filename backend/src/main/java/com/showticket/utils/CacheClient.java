package com.showticket.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.showticket.utils.RedisConstants.*;

/**
 * 缓存工具类：封装两种缓存问题模板
 * 1. queryWithPassThrough —— 缓存穿透：未命中查 DB，DB 也没有就写空串
 * 2. queryWithLogicalExpire —— 缓存击穿：缓存不设 TTL，靠字段判断逻辑过期，Redisson 分布式锁重建
 */
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;

    public CacheClient(StringRedisTemplate stringRedisTemplate, RedissonClient redissonClient) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
    }

    // 设置带 TTL 的普通缓存，value 转 JSON 字符串存
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    // 设置逻辑过期缓存：key 本身不设 TTL，过期时间写在 value 里，由调用方自己判断
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透模板
     *
     * @param keyPrefix  缓存 key 前缀，最终 key = keyPrefix + id
     * @param dbFallback 查库回调，缓存未命中时才会执行
     * @return 缓存或 DB 中的对象；不存在时返回 null（同时在缓存里留下空串占位）
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Long time, TimeUnit unit, Function<ID, R> dbFallback) {
        String key = keyPrefix + id;
        // 1. 先查缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 2. 不 blank 但非 null，说明命中之前写入的空串，DB 也没这条数据，直接返回
        if (json != null) {
            return null;
        }
        // 3. key 根本不存在，只能查库
        R r = dbFallback.apply(id);
        // 4. DB 也没有，写空串占位，防止同一个 id 反复打 DB
        if (r == null) {
            this.set(key, "", time, unit);
            return null;
        }
        // 5. DB 查到，回写缓存
        this.set(key, JSONUtil.toJsonStr(r), time, unit);
        return r;
    }

    /**
     * 缓存击穿模板（逻辑过期）
     *
     * 缓存本身不设 TTL，只把过期时间写到数据里；发现逻辑过期后用 Redisson 分布式锁重建：
     * 抢到锁的线程查库重建，抢不到的线程直接返回旧数据，保证同一时刻只有一条请求打 DB、
     * 且任何请求都不会被锁阻塞。
     *
     * 抢锁用 tryLock(0)：抢不到立即返回 false；不给 leaseTime → 看门狗自动续期，
     * 重建多久都不会过期误删；finally 里按持有者校验后解锁，防误删/漏解。
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 缓存都没预热过，直接返回 null（这个模板不负责穿透兜底）
        if (StrUtil.isBlank(json)) {
            return null;
        }

        // 存在，看逻辑过期时间有没有到
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        // 没逻辑过期，直接用
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return r;
        }

        // 已逻辑过期：抢 Redisson 分布式锁，抢不到直接返回旧数据（别人正在重建）
        RLock lock = redissonClient.getLock(LOCK_VENUE_KEY + id);
        boolean acquired;
        try {
            acquired = lock.tryLock(0, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return r;
        }
        if (!acquired) {
            return r;
        }
        try {
            // 双检：可能上一个持锁线程刚重建完
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                return JSONUtil.toBean(json, type);
            }
            // 缓存里是空串，说明 DB 也没这条数据，返回 null
            if (json != null) {
                return null;
            }
            // 同步重建：抢到锁的线程本来就是要查库的那一个，其他请求已返回旧数据、不被阻塞
            R rebuilt = dbFallback.apply(id);
            this.setWithLogicalExpire(key, rebuilt, time, unit);
            return rebuilt;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}