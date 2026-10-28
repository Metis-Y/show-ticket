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

@Slf4j
@Component
//redis工具类
public class CacheClient {
    private StringRedisTemplate stringRedisTemplate;
    //自动注入
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //设置普通缓存
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }
    //设置逻辑过期时间
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {// 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));// 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //<R,ID> R---前面分别是两个泛型，后面R是返回值类型
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,Long time, TimeUnit unit, Function<ID,R> dbFallback) {

        String key = keyPrefix + id;
        // 1.从redis查询场馆缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在，存在则返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空值
        if (json != null) {
            // 返回一个错误信息
            return null;
        }
        //查数据库
        R r = dbFallback.apply(id);
        //6.不存在，写入""
        if (r == null) {
            this.set(key, "",time, unit);
            return null;
        }
        //存在，写入redis
        this.set(key, JSONUtil.toJsonStr(r), time, unit);
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    //线程池+逻辑过期
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        String key =keyPrefix + id;
        String venueJson = stringRedisTemplate.opsForValue().get(key);
        //不存在返回null
        if (StrUtil.isBlank(venueJson)) {
            return null;
        }

        //存在，查看时间有没有过期
        RedisData redisData = JSONUtil.toBean(venueJson, RedisData.class);
        //序列化
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        //没过期，返回数据
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return r;
        }

        //过期
        String lockKey = LOCK_VENUE_KEY + id;

        //获得锁
        if (tryLock(lockKey)) {
            //获得锁再判断一次
            venueJson = stringRedisTemplate.opsForValue().get(key);
            //存在直接返回
            if (StrUtil.isNotBlank(venueJson)) {
                return JSONUtil.toBean(venueJson, type);
            }
            //缓存没有，返回null
            //这里确定venueJson是否为数据库没有该数据时设置的""
            if (venueJson != null) {
                return null;
            }
            //实现缓存重建
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
        //没获得锁,返回旧数据
        return r;

    }

    //互斥锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_VENUE_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


}