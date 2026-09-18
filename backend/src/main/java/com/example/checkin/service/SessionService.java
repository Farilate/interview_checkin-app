package com.example.checkin.service;

/**
 * 登录会话的创建、查询与删除入口。
 * <p>原始令牌由客户端持有，服务端通过其摘要定位 Redis 会话；会话丢失后必须重新登录。
 */
public interface SessionService {

    /**
     * 为已完成密码验证的用户创建独立会话，不撤销该用户的其他会话。
     * @param userId 从数据库取得的用户 ID
     * @return Redis 写入成功后可交给客户端的原始令牌
     */
    String createSession(long userId);

    /**
     * 查询会话身份，不延长有效期，也不校验数据库中的用户是否存在。
     * @param token 客户端提交的原始令牌，而非摘要
     * @return 会话中的用户 ID；会话不存在或已过期时返回 null
     */
    Long getUserId(String token);

    /**
     * 删除指定令牌对应的会话；键已不存在时正常结束，Redis 故障向上传递。
     * @param token 要撤销的原始令牌，不影响同一用户的其他令牌
     */
    void deleteSession(String token);
}
