package com.example.contract;

import com.example.checkin.common.ApiResponse;
import com.example.checkin.common.ErrorCode;
import com.example.checkin.config.ApiResponseAdvice;
import com.example.checkin.config.WebMvcConfig;
import com.example.checkin.auth.AuthInterceptor;
import com.example.checkin.service.AuthService;
import com.example.checkin.service.SessionService;
import org.springframework.context.annotation.Bean;
import com.example.checkin.dto.PageRequest;
import com.example.checkin.exception.ApiErrorController;
import com.example.checkin.exception.BusinessException;
import com.example.checkin.exception.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.validation.BindException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.checkin.dto.CurrentUserResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段 3 的真实 HTTP 契约测试：启动随机端口的 Web 容器，验证生产异常处理与 JSON 配置。
 * 测试 Controller 仅用于触发成功、参数错误和故障，不代表已实现任何业务接口。
 * 本类放在应用扫描包外，测试配置不会混入持久层测试；不连接 MySQL 或 Redis。
 */
@SpringBootTest(classes = ApiContractTest.WebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApiContractTest {
    /** 随机端口避免占用开发服务端口。 */
    @LocalServerPort int port;
    /** 仅提供认证所需的业务结果，MVC 路由与拦截器仍使用生产实现。 */
    @Autowired SessionService sessionService;
    @Autowired AuthService authService;
    @Autowired org.springframework.core.env.ConfigurableEnvironment environment;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    /** test profile 不得通过显式 import 偷渡本机 local 配置；只检查来源名称，不输出配置值。 */
    @Test
    void testProfileDoesNotLoadLocalConfiguration() {
        assertTrue(java.util.Arrays.asList(environment.getActiveProfiles()).contains("test"));
        assertFalse(java.util.Arrays.asList(environment.getActiveProfiles()).contains("local"));
        assertFalse(environment.getPropertySources().stream()
                .anyMatch(source -> source.getName().contains("application-local")));
    }

    /** 认证通过后，未知受保护路径应继续进入路由缺失处理，返回 40400 而非 40101。 */
    @Test
    void authenticatedUnknownApiRouteIs404() throws Exception {
        org.mockito.Mockito.when(sessionService.getUserId("valid-route-test-token")).thenReturn(7L);
        org.mockito.Mockito.when(authService.getCurrentUser(7L))
                .thenReturn(new CurrentUserResponse("7", "demo"));
        try {
            check(client.send(HttpRequest.newBuilder(uri("/api/v1/nonexistent-contract-route"))
                            .timeout(Duration.ofSeconds(10))
                            .header("Authorization", "Bearer valid-route-test-token").GET().build(),
                    HttpResponse.BodyHandlers.ofString()), 404, 40400);
            org.mockito.Mockito.verify(authService).getCurrentUser(7L);
        } finally {
            // Spring 上下文复用同一组 Bean，清除桩与调用记录，避免影响其他测例。
            org.mockito.Mockito.reset(sessionService, authService);
        }
    }

    /** 独立装配 Web 层，复用 application.yml，但关闭本测试不需要的数据源自动配置。 */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
    @Import({GlobalExceptionHandler.class, ApiResponseAdvice.class, ApiErrorController.class, ProbeController.class,
            WebMvcConfig.class, AuthInterceptor.class})
    static class WebApplication {
        /** 仅替换存储相关服务；拦截器及其路径注册均使用生产配置。 */
        @Bean
        SessionService sessionService() { return org.mockito.Mockito.mock(SessionService.class); }

        /** 无令牌请求应在调用认证业务前拒绝，不需要真实数据库。 */
        @Bean
        AuthService authService() { return org.mockito.Mockito.mock(AuthService.class); }
    }

    /**
     * 锁定生产默认 MVC 资源映射下的边界：受保护前缀的未知路径先经过认证。
     * 通过真实 HTTP 容器和生产 WebMvcConfig 验证，不在测试中手工注册路径规则。
     */
    @Test
    void unauthenticatedUnknownApiRouteIs401() throws Exception {
        check(send("GET", "/api/v1/nonexistent-contract-route", null), 401, 40101);
    }

    /** 成功响应保持结构和 201 状态；空数据仍明确输出 data:null。 */
    @Test
    void successAndCreatedResponses() throws Exception {
        JsonNode body = check(send("GET", "/probe/success", null), 200, 0);
        assertEquals("ok", body.get("message").asString());
        assertEquals("9007199254740993", body.get("data").get("id").asString());
        assertTrue(check(send("GET", "/probe/empty", null), 200, 0).get("data").isNull());
        assertEquals("阅读", check(send("POST", "/probe/body", "{\"name\":\"阅读\"}"), 201, 0)
                .get("data").get("name").asString());
    }

    /** 每种业务错误都必须使用文档约定的 HTTP 状态、错误码和安全提示。 */
    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void businessErrors(ErrorCode code) throws Exception {
        JsonNode body = check(send("GET", "/probe/business/" + code.name(), null),
                code.getHttpStatus().value(), code.getCode());
        assertEquals(code.getDefaultMessage(), body.get("message").asString());
    }

    /** 校验失败、未知字段、伪造 userId、JSON 损坏和多余内容均拒绝。 */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":\" \"}", "{\"name\":null}", "{",
            "{\"name\":\"x\",\"userId\":1}", "{\"name\":\"x\",\"extra\":true}",
            "{\"name\":\"x\"} {}", "null", ""})
    void invalidBodies(String body) throws Exception {
        check(send("POST", "/probe/body", body), 400, 40001);
    }

    /** 查询参数缺失、类型不符和超出分页范围，统一使用 40001。 */
    @ParameterizedTest
    @ValueSource(strings = {"/probe/required", "/probe/required?number=abc", "/probe/required?number=0",
            "/probe/page?page=0", "/probe/page?pageSize=101", "/probe/page?pageSize=0", "/probe/page?page=abc"})
    void invalidParameters(String path) throws Exception {
        check(send("GET", path, null), 400, 40001);
    }

    /** 分页默认值与大页码偏移不应发生 int 溢出。 */
    @Test
    void validPagination() throws Exception {
        JsonNode defaults = check(send("GET", "/probe/page", null), 200, 0).get("data");
        assertEquals(1, defaults.get("page").asInt());
        assertEquals(20, defaults.get("pageSize").asInt());
        assertEquals(214748364600L, check(send("GET", "/probe/page?page=2147483647&pageSize=100", null),
                200, 0).get("data").get("offset").asLong());
    }

    /** 框架 404/405 与容器错误分派都必须返回 JSON，405 保留 Allow 协议头。 */
    @Test
    void routingAndContainerErrors() throws Exception {
        check(send("GET", "/missing-route", null), 404, 40400);
        HttpResponse<String> method = send("POST", "/probe/success", "{}");
        check(method, 405, 40500);
        assertTrue(method.headers().firstValue("Allow").orElse("").contains("GET"));
        check(send("GET", "/probe/container", null), 500, 50001);
        check(send("GET", "/error", null), 404, 40400);
    }

    /** 媒体类型错误仍使用统一结构，而非默认 HTML 或 ProblemDetail。 */
    @Test
    void unsupportedMediaTypes() throws Exception {
        check(client.send(HttpRequest.newBuilder(uri("/probe/body"))
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("x")).build(),
                HttpResponse.BodyHandlers.ofString()), 415, 41500);
        check(client.send(HttpRequest.newBuilder(uri("/probe/success"))
                .header("Accept", "application/xml").GET().build(), HttpResponse.BodyHandlers.ofString()), 406, 40600);
    }

    /** 连接与临时事务错误返回 503，未知唯一键冲突和内部异常不能伪装业务成功。 */
    @ParameterizedTest
    @ValueSource(strings = {"connection", "transient", "duplicate", "internal"})
    void internalDetailsNeverLeak(String failure) throws Exception {
        boolean unavailable = failure.equals("connection") || failure.equals("transient");
        check(send("GET", "/probe/failure/" + failure, null), unavailable ? 503 : 500,
                unavailable ? 50302 : 50001);
    }

    /** 通用框架异常仅携带状态时不能猜测凭证、路由或基础设施的业务原因。 */
    @ParameterizedTest
    @ValueSource(ints = {400, 401, 404, 405, 406, 415, 503})
    void statusAloneDoesNotSelectBusinessCode(int status) throws Exception {
        check(send("GET", "/probe/status/" + status, null), 500, 50001);
    }

    /** 容器仅保留无异常 404 的路由语义，其余状态安全回退到内部错误。 */
    @ParameterizedTest
    @ValueSource(ints = {400, 401, 404, 405, 406, 415, 503})
    void containerStatusDoesNotGuessBusinessCause(int status) throws Exception {
        check(send("GET", "/probe/container/" + status, null),
                status == 404 ? 404 : 500, status == 404 ? 40400 : 50001);
    }

    /** 服务端返回值校验、路径配置和转换器配置错误不能误报客户端参数错误。 */
    @ParameterizedTest
    @ValueSource(strings = {"return-value", "conversion", "missing-path"})
    void serverValidationErrorsRemain500(String failure) throws Exception {
        check(send("GET", "/probe/" + failure, null), 500, 50001);
    }

    /** 普通绑定异常依然返回 400，覆盖父类未直接注册的 BindException。 */
    @Test
    void bindingErrorIs400() throws Exception {
        check(send("GET", "/probe/bind", null), 400, 40001);
    }

    /** 容器 404 同时带有未知异常时，不掩盖异常并误报路由不存在。 */
    @Test
    void containerExceptionTakesPrecedenceOver404() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE, 404);
        request.setAttribute(jakarta.servlet.RequestDispatcher.ERROR_EXCEPTION,
                new IllegalStateException("private-secret"));
        var response = new ApiErrorController().error(request);
        assertEquals(500, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(50001, response.getBody().code());
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    /** 未知异常保留排查位置，日志和响应均不泄露异常原文中的敏感样本。 */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void unexpectedLogsAreSafe(CapturedOutput output) throws Exception {
        check(send("GET", "/probe/failure/internal", null), 500, 50001);
        assertTrue(output.getAll().contains("IllegalStateException"));
        assertTrue(output.getAll().contains("ApiContractTest"));
        assertFalse(output.getAll().contains("private-secret"));
        assertFalse(output.getAll().contains("SELECT password_hash"));
    }

    /** 统一发送请求，给网络调用设置超时，避免失败时无限等待。 */
    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 所有请求仅发送到本测试随机端口。 */
    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    /** 核对三字段契约、错误 data:null、缓存头，以及敏感信息不泄露。 */
    private JsonNode check(HttpResponse<String> response, int status, int code) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        JsonNode body = json.readTree(response.body());
        assertEquals(3, body.size());
        assertEquals(code, body.get("code").asInt());
        assertTrue(body.get("message").isString());
        assertNotNull(body.get("data"));
        if (code != 0) assertTrue(body.get("data").isNull());
        assertFalse(response.body().contains("private-secret"));
        assertFalse(response.body().contains("SELECT password_hash"));
        assertFalse(response.body().contains("Exception"));
        return body;
    }

    /** 测试专用 DTO，仅演示标准 @Valid；生产业务 DTO 在对应阶段实现。 */
    record ProbeRequest(@NotBlank String name) { }

    /** 所有探针仅编译到测试目录，不会出现在生产 JAR 中。 */
    @RestController
    @RequestMapping("/probe")
    static class ProbeController {
        /** 返回字符串形式的大 ID，验证统一包装不会损失数字精度。 */
        @GetMapping("/success")
        ApiResponse<?> success() { return ApiResponse.ok(Map.of("id", "9007199254740993")); }

        /** 无业务数据的成功响应也必须包含 data 字段。 */
        @GetMapping("/empty")
        ApiResponse<?> empty() { return ApiResponse.ok(); }

        /** 验证请求体反序列化、Bean Validation 和创建资源状态；不执行真实业务创建。 */
        @PostMapping("/body")
        ResponseEntity<?> create(@Valid @RequestBody ProbeRequest request) {
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(request));
        }

        /** 使用生产分页 DTO，验证默认值、绑定、范围校验与偏移计算。 */
        @GetMapping("/page")
        ApiResponse<?> page(@Valid @ModelAttribute PageRequest page) {
            return ApiResponse.ok(Map.of("page", page.getPage(), "pageSize", page.getPageSize(), "offset", page.offset()));
        }

        /** 触发必填、类型转换及 Spring MVC 原生方法参数校验。 */
        @GetMapping("/required")
        ApiResponse<?> required(@RequestParam @Min(1) int number) { return ApiResponse.ok(number); }

        /** 用每个预定义错误码检查全局业务异常出口。 */
        @GetMapping("/business/{code}")
        ApiResponse<?> business(@PathVariable ErrorCode code) { throw new BusinessException(code); }

        /** 主动制造带敏感样本文本的异常，只验证分类和脱敏，不模拟持久化成功。 */
        @GetMapping("/failure/{failure}")
        ApiResponse<?> failure(@PathVariable String failure) {
            String unsafe = "private-secret SELECT password_hash FROM users";
            throw switch (failure) {
                case "connection" -> new DataAccessResourceFailureException(unsafe);
                case "transient" -> new TransientDataAccessResourceException(unsafe);
                case "duplicate" -> new DuplicateKeyException(unsafe);
                default -> new IllegalStateException(unsafe);
            };
        }

        /** 使用容器 sendError 触发 /error 分派，覆盖 MVC 异常处理之外的兜底路径。 */
        @GetMapping("/container")
        void container(HttpServletResponse response) throws java.io.IOException {
            response.sendError(500, "private-secret");
        }

        /** 仅提供状态和敏感原因，不提供可识别业务语义。 */
        @GetMapping("/status/{status}")
        void status(@PathVariable int status) {
            throw new ResponseStatusException(HttpStatus.valueOf(status), "private-secret");
        }

        /** 验证各种原始容器状态不会被错误猜测为某个业务错误。 */
        @GetMapping("/container/{status}")
        void containerStatus(@PathVariable int status, HttpServletResponse response) throws java.io.IOException {
            response.sendError(status, "private-secret");
        }

        /** 返回值违反服务端声明属于程序错误，而非请求参数错误。 */
        @Min(1)
        @GetMapping("/return-value")
        int invalidReturnValue() { return 0; }

        /** 缺少转换器配置与客户端传入不可转换的数字属于不同错误类型。 */
        @GetMapping("/conversion")
        void conversion() { throw new ConversionNotSupportedException("private-secret", Integer.class, null); }

        /** 故意缺少路由占位符，模拟服务端声明配置错误。 */
        @GetMapping("/missing-path")
        void missingPath(@PathVariable String absent) { }

        /** 普通绑定异常没有状态推断，按异常类型直接映射。 */
        @GetMapping("/bind")
        void binding() throws BindException { throw new BindException(new Object(), "probe"); }
    }
}
