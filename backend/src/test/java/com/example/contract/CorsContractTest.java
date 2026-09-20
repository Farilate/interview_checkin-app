package com.example.contract;

import com.example.checkin.auth.AuthInterceptor;
import com.example.checkin.config.ApiResponseAdvice;
import com.example.checkin.config.WebMvcConfig;
import com.example.checkin.controller.AuthController;
import com.example.checkin.exception.GlobalExceptionHandler;
import com.example.checkin.service.AuthService;
import com.example.checkin.service.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/** 使用真实 HTTP 容器与生产 MVC 配置验证 CORS；替换存储服务，不访问数据库或 Redis。 */
@SpringBootTest(classes = CorsContractTest.WebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.cors.allowed-origin=http://localhost:5173")
@ActiveProfiles("test")
class CorsContractTest {
    @LocalServerPort int port;
    @MockitoBean AuthService authService;
    @MockitoBean SessionService sessionService;
    @Autowired AuthInterceptor interceptor;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** 仅装配认证 Web 层，CORS 规则完全来自生产配置。 */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
    @Import({WebMvcConfig.class, AuthInterceptor.class, AuthController.class,
            GlobalExceptionHandler.class, ApiResponseAdvice.class})
    static class WebApplication { }

    /** 登录 JSON 预检可匿名通过，返回精确来源、方法、请求头及缓存时长。 */
    @Test
    void loginPreflightAllowsConfiguredOrigin() throws Exception {
        var response = preflight("/auth/login", "http://localhost:5173", "POST", "content-type");
        assertEquals(200, response.statusCode());
        assertEquals("http://localhost:5173", response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals("3600", response.headers().firstValue("Access-Control-Max-Age").orElseThrow());
        assertTrue(response.headers().firstValue("Access-Control-Allow-Methods").orElseThrow().contains("POST"));
        assertEquals("content-type", response.headers().firstValue("Access-Control-Allow-Headers").orElseThrow().toLowerCase());
        assertTrue(response.headers().firstValue("Access-Control-Allow-Credentials").isEmpty());
        verifyNoInteractions(authService, sessionService);
    }

    /** 受保护接口允许 Authorization 预检，但这不等于授权实际业务请求。 */
    @Test
    void protectedPreflightAllowsAuthorization() throws Exception {
        var response = preflight("/auth/me", "http://localhost:5173", "GET", "authorization,content-type");
        assertEquals(200, response.statusCode());
        assertEquals("http://localhost:5173", response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        String headers = response.headers().firstValue("Access-Control-Allow-Headers").orElseThrow().toLowerCase();
        assertTrue(headers.contains("authorization"));
        assertTrue(headers.contains("content-type"));
        verifyNoInteractions(authService, sessionService);
    }

    /** 同一来源真正发起无令牌 GET 时，仍返回 40101，且浏览器可读取该响应。 */
    @Test
    void actualUnauthenticatedRequestRemainsUnauthorized() throws Exception {
        var response = client.send(builder("/auth/me").header("Origin", "http://localhost:5173").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        assertEquals(40101, JsonMapper.builder().build().readTree(response.body()).get("code").asInt());
        assertEquals("http://localhost:5173", response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        verifyNoInteractions(authService, sessionService);
    }

    /** 未配置来源、方法和请求头均不得通过预检，防止意外放宽跨域范围。 */
    @Test
    void rejectsUnconfiguredCorsRequests() throws Exception {
        assertEquals(403, preflight("/auth/login", "http://localhost:5174", "POST", "content-type").statusCode());
        assertEquals(403, preflight("/auth/login", "http://localhost:5173", "PATCH", "content-type").statusCode());
        assertEquals(403, preflight("/auth/login", "http://localhost:5173", "POST", "x-unapproved").statusCode());
        verifyNoInteractions(authService, sessionService);
    }

    /** 即使直接进入认证拦截器，OPTIONS 也不应读取 Session 或写入当前用户属性。 */
    @Test
    void interceptorSkipsOptionsWithoutCreatingIdentity() throws Exception {
        var request = new MockHttpServletRequest("OPTIONS", "/api/v1/auth/me");
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertNull(request.getAttribute(AuthInterceptor.CURRENT_USER_ID));
        verifyNoInteractions(authService, sessionService);
    }

    private HttpRequest.Builder builder(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(10));
    }

    private HttpResponse<String> preflight(String path, String origin, String method, String headers) throws Exception {
        return client.send(builder(path).header("Origin", origin)
                        .header("Access-Control-Request-Method", method)
                        .header("Access-Control-Request-Headers", headers)
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
