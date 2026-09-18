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

/**
 * Servlet 容器错误分派的兜底出口，替代默认 HTML 错误页。
 * MVC 内的异常由 GlobalExceptionHandler 处理；sendError 等容器错误会分派到这里。
 * 不读取异常消息、请求参数或堆栈，避免默认错误属性泄露内部细节。
 */
@RestController
public class ApiErrorController implements ErrorController {
    /**
     * 只信任容器设置的错误状态属性；直接访问 /error 时返回路由不存在。
     * 显式指定 JSON 响应类型，即使浏览器 Accept 为 text/html 也不返回错误页面。
     */
    @RequestMapping("${spring.web.error.path:/error}")
    public ResponseEntity<ApiResponse<Void>> error(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        ErrorCode code = status instanceof Integer value
                ? ErrorCode.fromHttpStatus(value) : ErrorCode.ROUTE_NOT_FOUND;
        return ResponseEntity.status(code.getHttpStatus()).contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store").body(ApiResponse.error(code));
    }
}
