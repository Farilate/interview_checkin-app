package com.example.checkin.exception;

import com.example.checkin.common.ApiResponse;
import com.example.checkin.common.ErrorCode;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.extern.slf4j.Slf4j;
import java.util.Arrays;

/**
 * Servlet 容器错误分派的兜底出口，替代默认 HTML 错误页。
 * MVC 内的异常由 GlobalExceptionHandler 处理；sendError 等容器错误会分派到这里。
 * 不将异常消息、请求参数或堆栈放入响应，避免默认错误属性泄露内部细节。
 */
@RestController
@Slf4j
public class ApiErrorController implements ErrorController {
    /**
     * 仅将没有附带异常的容器 404 识别为路由不存在；直接访问 /error 也返回路由不存在。
     * 其他状态或带异常的错误分派均为未知内部错误，不据此猜测 401xx 或 503xx。
     * 显式指定 JSON 响应类型，即使浏览器 Accept 为 text/html 也不返回错误页面。
     */
    @RequestMapping("${spring.web.error.path:/error}")
    public ResponseEntity<ApiResponse<Void>> error(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        Object failure = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        ErrorCode code = failure == null && (status == null || Integer.valueOf(404).equals(status))
                ? ErrorCode.ROUTE_NOT_FOUND : ErrorCode.INTERNAL_ERROR;
        if (code == ErrorCode.INTERNAL_ERROR) {
            if (failure instanceof Throwable exception) {
                log.error("容器错误分派，异常类型：{}，堆栈位置：{}",
                        exception.getClass().getName(), Arrays.toString(exception.getStackTrace()));
            } else {
                log.error("容器错误分派缺少可识别原因，HTTP 状态：{}",
                        status instanceof Integer value ? value : "未知");
            }
        }
        return ResponseEntity.status(code.getHttpStatus()).contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store").body(ApiResponse.error(code));
    }
}
