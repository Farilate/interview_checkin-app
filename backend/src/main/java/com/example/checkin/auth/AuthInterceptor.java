package com.example.checkin.auth;

import com.example.checkin.common.ErrorCode;
import com.example.checkin.exception.BusinessException;
import com.example.checkin.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 在受保护接口执行前验证 Redis 登录会话，并将用户身份放入当前请求。
 * <p>拦截范围由 WebMvcConfig 注册；登录接口允许匿名访问。
 * 本类只检查会话，不查询 MySQL 中用户是否仍然存在，也不负责资源归属校验。
 * Redis 访问异常向上传递，不会将故障当作认证成功。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 服务端请求属性名，供 Controller 读取；与客户端提交的 userId 参数无关。 */
    public static final String CURRENT_USER_ID =
            "currentUserId";

    /** 当前实现要求大小写完全一致的 Bearer 前缀及其后的一个空格。 */
    private static final String BEARER_PREFIX =
            "Bearer ";

    private final SessionService sessionService;

    /** 通过构造器注入会话服务，拦截器自身不保存某个请求的用户信息。 */
    public AuthInterceptor(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * 在 Controller 调用前完成认证。
     * @param request 当前请求，认证成功后写入用户 ID 属性
     * @param response 当前响应，失败响应由统一异常处理器生成
     * @param handler 本次请求匹配的处理器
     * @return 会话存在时返回 true，允许继续处理请求
     * @throws BusinessException 请求头缺失、格式错误、令牌为空或会话不存在时抛出未登录异常
     */
    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {

        String authorization =
                request.getHeader("Authorization");

        if (authorization == null
                || !authorization.startsWith(BEARER_PREFIX)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // 只去掉令牌两端空白；完整令牌不写入日志，也不作为 Redis Key 明文保存。
        String token = authorization
                .substring(BEARER_PREFIX.length())
                .trim();

        if (token.isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // 查询不会续期；过期、登出删除或不存在的会话均返回 null。
        Long userId = sessionService.getUserId(token);

        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // 属性只属于本次请求，无须像 ThreadLocal 一样在线程复用时额外清理。
        request.setAttribute(CURRENT_USER_ID, userId);

        return true;
    }
}
