package com.showticket.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 30L;

    //缓存空对象的ttl,封装后用不上
    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_VENUE_TTL = 30L;
    public static final String CACHE_VENUE_KEY = "cache:venue:";

    public static final String LOCK_VENUE_KEY = "lock:venue:";
    public static final Long LOCK_VENUE_TTL = 10L;

    /** 抢票库存，value 为剩余票数 */
    public static final String GRAB_STOCK_KEY = "ticket:stock:";
    /** 开售窗口，hash：begin / end（epoch 秒） */
    public static final String SHOW_WINDOW_KEY = "ticket:window:";
    /** 已抢到票的用户集合，用于一人一票 */
    public static final String TICKET_ORDER_KEY = "ticket:order:";
    /** 场馆详情页 UV（HyperLogLog） */
    public static final String VENUE_UV_KEY = "venue:uv:";

    public static final String REVIEW_LIKED_KEY = "review:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String VENUE_GEO_KEY = "venue:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
