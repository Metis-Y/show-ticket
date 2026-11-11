package com.showticket.utils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流：Redis 令牌桶。标在 Controller / Service 方法入口。
 * capacity=桶容量（允许的突发上限），rate=每秒补发速率（限住的平均速率）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    int capacity();

    double rate();
}