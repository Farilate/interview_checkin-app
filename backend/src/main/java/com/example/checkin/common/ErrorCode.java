package com.example.checkin.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * REST API 统一业务错误码。
 *
 * <p>错误码定义必须与 docs/API.md 保持同步。
 * Controller 和 Service 不应散落 40001、40101 等魔法数字。
 * 错误原因由业务边界或具体异常类型确定；仅支持错误码到 HTTP 状态的正向转换。
 */
@Getter
public enum ErrorCode {

    INVALID_ARGUMENT(
            40001,
            "请求参数不合法"
    ),

    UNAUTHORIZED(
            40101,
            "未登录或登录状态已失效"
    ),

    INVALID_CREDENTIALS(
            40102,
            "用户名或密码错误"
    ),

    HABIT_NOT_FOUND(
            40401,
            "打卡项不存在"
    ),

    ROUTE_NOT_FOUND(
            40400,
            "请求地址不存在"
    ),

    METHOD_NOT_ALLOWED(
            40500,
            "请求方法不支持"
    ),

    HABIT_NAME_CONFLICT(
            40901,
            "已存在同名打卡项"
    ),

    NOT_ACCEPTABLE(
            40600,
            "不支持请求的响应格式"
    ),

    UNSUPPORTED_MEDIA_TYPE(
            41500,
            "不支持请求的数据格式"
    ),

    REDIS_SESSION_UNAVAILABLE(
            50301,
            "登录服务暂时不可用"
    ),

    DATABASE_UNAVAILABLE(
            50302,
            "数据服务暂时不可用"
    ),

    INTERNAL_ERROR(
            50001,
            "服务器内部错误"
    );

    private final int code;
    private final String defaultMessage;

    /** 错误码前三位对应 HTTP 状态，集中映射，避免 Controller 各自填写状态码。 */
    public HttpStatus getHttpStatus() {
        return HttpStatus.valueOf(code / 100);
    }


    ErrorCode(
            int code,
            String defaultMessage
    ) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

}
