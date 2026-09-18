package com.example.checkin.config;

import com.example.checkin.auth.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 配置。
 *
 * <p>统一注册需要登录认证的请求拦截规则。
 * 登录接口本身必须允许匿名访问，否则用户无法获取登录 Token。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    /** 复用容器中的认证拦截器，避免自行创建会话服务或维护登录状态。 */
    public WebMvcConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    /**
     * 注册认证拦截器。
     *
     * <p>所有 /api/v1/** 接口默认要求登录，
     * 仅显式排除登录接口，当前用户查询和登出仍须认证。
     * <p>这里未配置跨域规则，也未针对 OPTIONS 预检请求作单独处理。
     * @param registry Spring MVC 提供的拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/auth/login");
    }
}
