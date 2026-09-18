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
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Arrays;

/**
 * 所有 MVC 接口共用的异常出口，保持 code/message/data 格式。
 * 父类识别请求体校验、JSON 解析、参数转换、路由及请求方法等框架异常；
 * 本类按异常类型选择错误码并替换响应，保留框架提供的 Allow 等协议头。
 * 不重复注册父类已经声明的 MVC 异常，避免异常处理器映射冲突。
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
    @ExceptionHandler({ConstraintViolationException.class, BindException.class})
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

    /** 未预期异常统一返回安全消息；日志记录类型和堆栈位置，不记录可能含凭证的异常原文。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> internalFailure(Exception exception) {
        logUnexpected(exception);
        return response(ErrorCode.INTERNAL_ERROR, new HttpHeaders());
    }

    /**
     * 框架异常集中转为统一 DTO，忽略父类生成的 ProblemDetail 和原始异常文本。
     * 由父类具体异常处理器调用，不使用传入的 HTTP 状态反推原因。
     * 返回值校验和服务器转换器配置错误必须区别于客户端输入错误。
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ErrorCode code = frameworkCode(exception);
        if (code == ErrorCode.INTERNAL_ERROR) {
            logUnexpected(exception);
        }
        return response(code, headers);
    }

    /** 只识别明确的框架异常；通用 ErrorResponseException 即使携带 401/404/503 也不猜业务含义。 */
    private ErrorCode frameworkCode(Exception exception) {
        return switch (exception) {
            case HttpRequestMethodNotSupportedException ignored -> ErrorCode.METHOD_NOT_ALLOWED;
            case HttpMediaTypeNotAcceptableException ignored -> ErrorCode.NOT_ACCEPTABLE;
            case HttpMediaTypeNotSupportedException ignored -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case NoHandlerFoundException ignored -> ErrorCode.ROUTE_NOT_FOUND;
            case NoResourceFoundException ignored -> ErrorCode.ROUTE_NOT_FOUND;
            // 两类异常虽然继承输入异常基类，实际表示服务端配置错误，须先匹配。
            case ConversionNotSupportedException ignored -> ErrorCode.INTERNAL_ERROR;
            case MissingPathVariableException ignored -> ErrorCode.INTERNAL_ERROR;
            case HandlerMethodValidationException validation -> validation.isForReturnValue()
                    ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_ARGUMENT;
            case MethodArgumentNotValidException ignored -> ErrorCode.INVALID_ARGUMENT;
            case ServletRequestBindingException ignored -> ErrorCode.INVALID_ARGUMENT;
            case MissingServletRequestPartException ignored -> ErrorCode.INVALID_ARGUMENT;
            case TypeMismatchException ignored -> ErrorCode.INVALID_ARGUMENT;
            case HttpMessageNotReadableException ignored -> ErrorCode.INVALID_ARGUMENT;
            default -> ErrorCode.INTERNAL_ERROR;
        };
    }

    /** 保留排查位置，不直接记录 Throwable，防止异常消息及 cause 泄露敏感数据。 */
    private void logUnexpected(Exception exception) {
        log.error("请求处理失败，异常类型：{}，堆栈位置：{}",
                exception.getClass().getName(), Arrays.toString(exception.getStackTrace()));
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
