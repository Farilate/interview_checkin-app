package com.example.checkin.auth;

import com.example.checkin.common.ErrorCode;
import com.example.checkin.exception.BusinessException;
import com.example.checkin.service.SessionService;
import com.example.checkin.service.AuthService;
import org.jspecify.annotations.NullMarked;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 在受保护接口执行前验证 Redis 登录会话，并将用户身份放入当前请求。
 * <p>拦截范围由 WebMvcConfig 注册；登录接口允许匿名访问。
 * 会话有效后还检查 MySQL 用户是否存在，缺失则撤销当前会话；不负责习惯等资源归属校验。
 * Redis 会话访问故障由会话服务转换为 50301，数据库查询故障交给统一异常处理器。
 * 任一步骤失败均不放行，也不写入当前用户属性。
 */
@Component
@NullMarked
public class AuthInterceptor implements HandlerInterceptor {

    /** 服务端请求属性名，供 Controller 读取；与客户端提交的 userId 参数无关。 */
    public static final String CURRENT_USER_ID =
            "currentUserId";

    /** 当前实现要求大小写完全一致的 Bearer 前缀及其后的一个空格。 */
    private static final String BEARER_PREFIX =
            "Bearer ";

    private final SessionService sessionService;
    private final AuthService authService;

    /** 注入会话服务和认证服务，分别查询会话身份与数据库用户；不保存跨请求用户状态。 */
    public AuthInterceptor(SessionService sessionService, AuthService authService) {
        this.sessionService = sessionService;
        this.authService = authService;
    }

    /**
     * 在 Controller 调用前完成认证。
     * @param request 当前请求，认证成功后写入用户 ID 属性
     * @param response 当前响应，失败响应由统一异常处理器生成
     * @param handler 本次请求匹配的处理器
     * @return 会话有效且数据库用户存在时返回 true，允许继续处理请求
     * @throws BusinessException 请求头或身份无效时为未登录；Redis 查询或清理故障时为会话不可用
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

        // 只在用户不存在时撤销当前令牌；数据库暂时不可用不能当作用户已删除。
        try {
            authService.getCurrentUser(userId);
        } catch (BusinessException exception) {
            if (exception.getErrorCode() == ErrorCode.UNAUTHORIZED) {
                sessionService.deleteSession(token);
            }
            throw exception;
        }

        // 属性只属于本次请求，无须像 ThreadLocal 一样在线程复用时额外清理。
        request.setAttribute(CURRENT_USER_ID, userId);

        return true;
    }
}
