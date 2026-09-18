package com.example.checkin.redis;

/**
 * Redis Key 统一管理。
 */
public final class RedisKeys {

    private static final String ROOT = "checkin:v1:";
    private static final String SESSION_PREFIX = ROOT + "session:";

    private RedisKeys() {
    }

    public static String session(String tokenHash) {
        return SESSION_PREFIX + tokenHash;
    }
}