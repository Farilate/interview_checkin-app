package com.example.checkin.config;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 统一禁止 API 响应缓存，包含成功响应和错误响应。
 * 不隐式包装返回对象：Controller 应显式返回 ApiResponse，创建资源时自行设置 HTTP 201。
 */
@RestControllerAdvice
@NullMarked
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {
    /** 当前项目均为 REST 接口，为所有响应应用同一缓存策略。 */
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    /** 保留原始 DTO，避免二次包装或改变原有 HTTP 状态。 */
    @Override
    public @Nullable Object beforeBodyWrite(@Nullable Object body, MethodParameter returnType, MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converterType,
            ServerHttpRequest request, ServerHttpResponse response) {
        response.getHeaders().setCacheControl("no-store");
        return body;
    }
}
