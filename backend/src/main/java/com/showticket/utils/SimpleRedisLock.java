package com.showticket.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的简易分布式锁
 *
 * 加锁用 SETNX + TTL；value 存「进程 UUID + 线程 id」，
 * 解锁前先用 Lua 比对 value，保证只能删自己的锁。
 */
public class SimpleRedisLock implements ILock{
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    // 锁 key 前缀，实际 key = 前缀 + 业务名
    private static final String KEY_PREFIX = "show-ticket:lock:";
    // 进程级唯一前缀：应用重启后 UUID 变化，避免误判旧锁归属
    private static final String ID_PREFIX = UUID.randomUUID().toString();

    // 加载解锁脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock( String name,StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 锁的持有者标识 = 进程 UUID + 线程 id，精确到线程
        String threadId = ID_PREFIX+Thread.currentThread().threadId();
        // SETNX + TTL：key 不存在才能加锁，同时写入过期时间避免死锁
        Boolean success=stringRedisTemplate.opsForValue()
                        .setIfAbsent(KEY_PREFIX+name,threadId , timeoutSec, TimeUnit.SECONDS);
        // 用 equals 拆箱，避免 null 拆箱报空指针
        return Boolean.TRUE.equals(success);
    }


    // 用 Lua 把「判断持有者 + 删除」合成一个原子操作，避免误删其他线程的锁
    @Override
    public void unlock() {

        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().threadId());
    }
    /*@Override
    public void unlock() {
        //获取当前线程id
        String threadId = KEY_PREFIX+Thread.currentThread().threadId();
        //获取当前用户id
        String id=stringRedisTemplate.opsForValue().get(KEY_PREFIX+name);
        //判断锁是否一致
        if (id.equals(threadId)){
            stringRedisTemplate.delete(KEY_PREFIX+name);
        }

    }*/
}
