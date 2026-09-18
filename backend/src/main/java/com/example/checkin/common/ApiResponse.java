package com.example.checkin.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * REST API 的统一响应结构。
 * <p>所有接口统一返回：
 * <pre>
 * {
 *   "code": 0,
 *   "message": "ok",
 *   "data": ...
 * }
 * </pre>
 * <p>成功时 code=0；
 * 失败时 data=null。
 * @param code    业务状态码
 * @param message 面向客户端的消息
 * @param data    响应数据
 */
// 即使业务数据为空也输出 data:null，保证前端看到稳定的三字段结构。
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResponse<T>(
        int code,
        String message,
        T data
) {

    /**
     * 创建带业务数据的成功响应。
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(
                0,
                "ok",
                data
        );
    }

    /**
     * 创建不带业务数据的成功响应。
     */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(
                0,
                "ok",
                null
        );
    }

    /**
     * 创建失败响应。
     */
    public static ApiResponse<Void> error(
            ErrorCode errorCode
    ) {
        return new ApiResponse<>(
                errorCode.getCode(),
                errorCode.getDefaultMessage(),
                null
        );
    }

}
