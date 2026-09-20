package com.example.checkin.redis;

import java.time.LocalDate;

/**
 * Redis Key 统一管理。
 */
public final class RedisKeys {

    /** 应用及键结构版本前缀。 */
    private static final String ROOT = "checkin:v1:";

    /** 登录会话命名空间。 */
    private static final String SESSION_PREFIX = ROOT + "session:";

    /** 今日打卡状态缓存命名空间。 */
    private static final String TODAY_PREFIX = ROOT + "today:";

    /** 当前连续打卡天数缓存命名空间。 */
    private static final String STREAK_PREFIX = ROOT + "streak:";

    private RedisKeys() {
    }

    public static String session(String tokenHash) {
        return SESSION_PREFIX + tokenHash;
    }

    /**
     * 今日打卡状态缓存 Key。
     *
     * 格式：
     * checkin:v1:today:{uid}:{hid}:{yyyy-MM-dd}
     */
    public static String today(
            long userId,
            long habitId,
            LocalDate businessDate) {

        return TODAY_PREFIX
                + userId + ":"
                + habitId + ":"
                + businessDate;
    }

    /**
     * 当前连续打卡天数缓存 Key。
     *
     * 格式：
     * checkin:v1:streak:{uid}:{hid}:{yyyy-MM-dd}
     */
    public static String streak(
            long userId,
            long habitId,
            LocalDate businessDate) {

        return STREAK_PREFIX
                + userId + ":"
                + habitId + ":"
                + businessDate;
    }
}