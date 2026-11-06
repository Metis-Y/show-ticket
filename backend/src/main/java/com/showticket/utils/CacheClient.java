package com.showticket.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.showticket.entity.Venue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.showticket.utils.RedisConstants.*;

/**
 * 缓存工具类：封装两种缓存问题模板
 * 1. queryWithPassThrough —— 缓存穿透：未命中查 DB，DB 也没有就写空串
 * 2. queryWithLogicalExpire —— 缓存击穿：缓存不设 TTL，靠字段判断逻辑过期，互斥锁重建
 */
@Slf4j
@Component
public class CacheClient {
    private StringRedisTemplate stringRedisTemplate;
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
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
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,Long time, TimeUnit unit, Function<ID,R> dbFallback) {

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
            this.set(key, "",time, unit);
            return null;
        }
        // 5. DB 查到，回写缓存
        this.set(key, JSONUtil.toJsonStr(r), time, unit);
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 缓存击穿模板（逻辑过期）
     *
     * 缓存本身不设 TTL，只把过期时间写到数据里；发现逻辑过期后抢互斥锁，
     * 抢到的线程开子线程重建缓存，抢不到的线程直接返回旧数据，
     * 保证同一时刻只有一条请求打 DB，且任何请求都不会被阻塞。
     */
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        String key =keyPrefix + id;
        String venueJson = stringRedisTemplate.opsForValue().get(key);
        // 缓存都没预热过，直接返回 null（这个模板不负责穿透兜底）
        if (StrUtil.isBlank(venueJson)) {
            return null;
        }

        // 存在，看逻辑过期时间有没有到
        RedisData redisData = JSONUtil.toBean(venueJson, RedisData.class);
        //序列化
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        // 没逻辑过期，直接用；下面都是已逻辑过期的处理
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return r;
        }

        // 已逻辑过期：抢重建锁，抢不到就直接用旧数据
        String lockKey = LOCK_VENUE_KEY + id;

        if (tryLock(lockKey)) {
            // 拿锁后再查一次缓存：可能上一个持锁线程刚重建完（双检）
            // 注意：下面两个 return 分支不会主动解锁，只能等 LOCK_VENUE_TTL 到期自然释放
            venueJson = stringRedisTemplate.opsForValue().get(key);
            //存在直接返回
            if (StrUtil.isNotBlank(venueJson)) {
                return JSONUtil.toBean(venueJson, type);
            }
            // 缓存里是空串，说明 DB 也没这条数据，返回 null
            if (venueJson != null) {
                return null;
            }
            // 异步重建，不阻塞当前请求；无论成败都在 finally 里解锁
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, r1, time, unit);
                }catch (Exception e) {
                    throw new RuntimeException(e);
                }finally{
                    unlock(lockKey);
                }
            });
        }
        // 没抢到锁，说明别人正在重建，直接用旧数据
        return r;

    }

    // SETNX + TTL 实现互斥锁，锁不区分持有者
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_VENUE_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    // 直接删 key，没有持有者校验：万一重建耗时超过 LOCK_VENUE_TTL，会误删别人的锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


}