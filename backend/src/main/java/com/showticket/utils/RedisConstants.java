package com.showticket.utils;

public class RedisConstants {

    /**
     * 所有 key 的统一前缀。
     * 多个项目共用同一个 Redis 实例时靠它隔离，避免 login:/feed: 这类通用 key 互相覆盖。
     */
    public static final String KEY_PREFIX = "show-ticket:";

    public static final String LOGIN_CODE_KEY = KEY_PREFIX + "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = KEY_PREFIX + "login:token:";
    public static final Long LOGIN_USER_TTL = 30L;

    /** 验证码发送限流：同一手机号 60 秒一次 */
    public static final String LOGIN_CODE_COOLDOWN_KEY = KEY_PREFIX + "login:code:cooldown:";
    public static final Long LOGIN_CODE_COOLDOWN_SEC = 60L;
    /** 验证码发送限流：同一 IP 每小时最多 10 次 */
    public static final String LOGIN_CODE_IP_KEY = KEY_PREFIX + "login:code:ip:";
    public static final Long LOGIN_CODE_IP_LIMIT = 10L;
    public static final Long LOGIN_CODE_IP_WINDOW_SEC = 3600L;
    /** 登录失败计数：连续 5 次锁定 15 分钟 */
    public static final String LOGIN_FAIL_KEY = KEY_PREFIX + "login:fail:";
    public static final Integer LOGIN_FAIL_MAX = 5;
    public static final Long LOGIN_FAIL_LOCK_SEC = 900L;

    //缓存空对象的ttl,封装后用不上
    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_VENUE_TTL = 30L;
    public static final String CACHE_VENUE_KEY = KEY_PREFIX + "cache:venue:";

    public static final String LOCK_VENUE_KEY = KEY_PREFIX + "lock:venue:";
    public static final Long LOCK_VENUE_TTL = 10L;

    /** 抢票库存，value 为剩余票数 */
    public static final String GRAB_STOCK_KEY = KEY_PREFIX + "ticket:stock:";
    /** 开售窗口，hash：begin / end（epoch 秒） */
    public static final String SHOW_WINDOW_KEY = KEY_PREFIX + "ticket:window:";
    /** 已抢到票的用户集合，用于一人一票 */
    public static final String TICKET_ORDER_KEY = KEY_PREFIX + "ticket:order:";
    /** 场馆详情页 UV（HyperLogLog） */
    public static final String VENUE_UV_KEY = KEY_PREFIX + "venue:uv:";

    public static final String REVIEW_LIKED_KEY = KEY_PREFIX + "review:liked:";
    public static final String FEED_KEY = KEY_PREFIX + "feed:";
    public static final String VENUE_GEO_KEY = KEY_PREFIX + "venue:geo:";
    public static final String USER_SIGN_KEY = KEY_PREFIX + "sign:";
    /** 关注关系集合 */
    public static final String FOLLOW_KEY = KEY_PREFIX + "follows:";
}