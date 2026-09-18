package com.example.checkin.redis;

/**
 * Redis Key 统一管理。
 */
public final class RedisKeys {

    /** 应用及键结构版本前缀，避免与其他业务键混用。 */
    private static final String ROOT = "checkin:v1:";
    /** 登录会话命名空间，值为用户 ID 字符串。 */
    private static final String SESSION_PREFIX = ROOT + "session:";

    /** 工具类只提供静态方法，不允许创建实例。 */
    private RedisKeys() {
    }

    /**
     * 构造会话键，本方法不计算摘要，也不访问 Redis。
     * @param tokenHash 原始令牌的 SHA-256 十六进制摘要，不能传入原始令牌
     * @return 带应用前缀的完整 Redis 会话键
     */
    public static String session(String tokenHash) {
        return SESSION_PREFIX + tokenHash;
    }
}
