package com.example.checkin.exception;

import com.example.checkin.common.ApiResponse;
import com.example.checkin.common.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 所有 MVC 接口共用的异常出口，保持 code/message/data 格式。
 * 父类识别请求体校验、JSON 解析、参数转换、路由及请求方法等框架异常；
 * 本类只替换最终响应，保留框架提供的 Allow 等协议头。
 */
@Slf4j
@RestControllerAdvice
@NullMarked
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** 已知业务失败：状态与提示由错误码决定，不让 Controller 重复编写 try/catch。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> business(BusinessException exception) {
        return response(exception.getErrorCode(), new HttpHeaders());
    }

    /** 校验器直接抛出的约束异常；不回显 rejectedValue，避免密码等输入泄露。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> validation() {
        return response(ErrorCode.INVALID_ARGUMENT, new HttpHeaders());
    }

    /** 数据库连接或临时事务故障返回 503，客户端可提示稍后重试。 */
    @ExceptionHandler({TransientDataAccessException.class, DataAccessResourceFailureException.class,
            CannotCreateTransactionException.class})
    public ResponseEntity<Object> databaseUnavailable(Exception exception) {
        log.error("数据库暂时不可用，异常类型：{}", exception.getClass().getSimpleName());
        return response(ErrorCode.DATABASE_UNAVAILABLE, new HttpHeaders());
    }

    /**
     * 未分类数据库错误返回 500，不能把所有唯一键冲突都当作重名或重复打卡成功。
     * 后续业务层应识别具体约束，再决定抛出业务异常或执行幂等查询。
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Object> databaseFailure(DataAccessException exception) {
        return internalFailure(exception);
    }

    /** 未预期异常统一返回安全消息，日志只记录类型，不写入 SQL、请求体、Token 或异常原文。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> internalFailure(Exception exception) {
        log.error("请求处理失败，异常类型：{}", exception.getClass().getSimpleName());
        return response(ErrorCode.INTERNAL_ERROR, new HttpHeaders());
    }

    /**
     * 框架异常集中转为统一 DTO，忽略父类生成的 ProblemDetail 和原始异常文本。
     * 请求体 @Valid、方法参数校验失败、类型转换错误通常为 400；返回值校验失败为 500。
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return response(ErrorCode.fromHttpStatus(status.value()), headers);
    }

    /** 构造 JSON 错误响应并禁止缓存；复制协议头，避免修改框架传入的只读对象。 */
    private ResponseEntity<Object> response(ErrorCode code, HttpHeaders source) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(source);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(ApiResponse.error(code), headers, code.getHttpStatus());
    }
}
