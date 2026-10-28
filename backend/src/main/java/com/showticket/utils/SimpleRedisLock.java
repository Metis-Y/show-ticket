package com.showticket.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    //key的前缀
    private static final String KEY_PREFIX = "show-ticket:lock:";
    //线程前缀
    private static final String ID_PREFIX = UUID.randomUUID().toString();

    //加载lua脚本
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
        //获取线程
        String threadId = ID_PREFIX+Thread.currentThread().threadId();
        //互斥锁
        Boolean success=stringRedisTemplate.opsForValue()
                        .setIfAbsent(KEY_PREFIX+name,threadId , timeoutSec, TimeUnit.SECONDS);
        //拆箱防止空指针
        return Boolean.TRUE.equals(success);
    }


    //使用lua脚本
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
