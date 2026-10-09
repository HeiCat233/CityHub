package com.tickethub.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOW_TTL = 30L;
    public static final String CACHE_SHOW_KEY = "cache:show:";

    public static final String LOCK_SHOW_KEY = "lock:show:";
    public static final Long LOCK_SHOW_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    public static final String POST_LIKED_KEY = "post:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOW_GEO_KEY = "show:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    public static final String SHOW_CATEGORY_KEY = "show:category:";
    public static final Long SHOW_CATEGORY_TTL = 10L;
}
