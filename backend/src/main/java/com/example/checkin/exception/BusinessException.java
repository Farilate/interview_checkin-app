package com.example.checkin.exception;

import com.example.checkin.common.ErrorCode;
import lombok.Getter;

import java.util.Objects;

/**
 * 业务层向统一异常处理器传递已知失败原因。
 * 只接受预定义错误码，客户端提示来自枚举，不接收可能包含凭证或 SQL 的原始异常消息。
 * 例如后续鉴权可使用 UNAUTHORIZED，习惯不存在或不属于当前用户均使用 HABIT_NOT_FOUND。
 */
@Getter
public class BusinessException extends RuntimeException {
    /** 与接口文档一致的错误分类，同时决定 HTTP 状态与安全提示。 */
    private final ErrorCode errorCode;

    /** @param errorCode 预定义业务错误，不允许为空 */
    public BusinessException(ErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode).getDefaultMessage());
        this.errorCode = errorCode;
    }
}
