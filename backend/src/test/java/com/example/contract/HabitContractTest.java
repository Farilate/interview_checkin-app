package com.example.contract;

import com.example.checkin.auth.AuthInterceptor;
import com.example.checkin.config.ApiResponseAdvice;
import com.example.checkin.config.WebMvcConfig;
import com.example.checkin.controller.HabitController;
import com.example.checkin.dto.CurrentUserResponse;
import com.example.checkin.exception.GlobalExceptionHandler;
import com.example.checkin.mapper.HabitMapper;
import com.example.checkin.model.Habit;
import com.example.checkin.service.AuthService;
import com.example.checkin.service.SessionService;
import com.example.checkin.service.impl.HabitServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Habit 真实 HTTP 回归：复用生产 Controller、Service、鉴权注册、校验与 JSON 配置。
 * 仅替换 Mapper 和认证依赖，不连接真实存储；SQL 排序与并发唯一性另由集成验收确认。
 * 测试位于应用扫描包外，避免测试 Bean 混入生产或 PersistenceIT 上下文。
 */
@SpringBootTest(classes = HabitContractTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HabitContractTest {
    @LocalServerPort int port;
    @Autowired HabitMapper habits;
    @Autowired SessionService sessions;
    @Autowired AuthService auth;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    /** 关闭数据源自动配置，显式装配当前 Habit 请求实际经过的生产组件。 */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
    @Import({HabitController.class, HabitServiceImpl.class, WebMvcConfig.class,
            AuthInterceptor.class, ApiResponseAdvice.class, GlobalExceptionHandler.class})
    static class Application {
        @Bean HabitMapper habits() { return mock(HabitMapper.class); }
        @Bean SessionService sessions() { return mock(SessionService.class); }
        @Bean AuthService auth() { return mock(AuthService.class); }
    }

    /** 每例清除共享 Bean 的桩与调用记录；两个令牌分别对应两个服务端身份。 */
    @BeforeEach
    void setUp() {
        reset(habits, sessions, auth);
        when(sessions.getUserId("expired")).thenReturn(null);
        when(sessions.getUserId("owner")).thenReturn(7L);
        when(sessions.getUserId("other")).thenReturn(8L);
        when(auth.getCurrentUser(7L)).thenReturn(new CurrentUserResponse("7", "owner"));
        when(auth.getCurrentUser(8L)).thenReturn(new CurrentUserResponse("8", "other"));
        when(habits.insert(any())).thenAnswer(call -> {
            Habit habit = call.getArgument(0);
            habit.setId(101L);
            return 1;
        });
    }

    /** 创建由会话确定所属用户；保存 trim 后名称及 UTC 时间，不向外暴露 userId。 */
    @Test
    void createUsesAuthenticatedOwnerAndUtcTime() throws Exception {
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
        JsonNode data = check(send("POST", "?userId=999", "owner",
                "{\"name\":\"  每日阅读  \",\"description\":\"阅读二十分钟\"}"), 200, 0).get("data");
        assertEquals(101, data.get("id").asLong());
        assertEquals("每日阅读", data.get("name").asString());
        assertEquals("阅读二十分钟", data.get("description").asString());
        assertFalse(data.has("userId"));
        var saved = org.mockito.ArgumentCaptor.forClass(Habit.class);
        verify(habits).insert(saved.capture());
        Habit habit = saved.getValue();
        assertEquals(7L, habit.getUserId());
        assertEquals("每日阅读", habit.getName());
        assertFalse(habit.getCreatedAt().isBefore(before));
        assertFalse(habit.getCreatedAt().isAfter(LocalDateTime.now(ZoneOffset.UTC)));
        assertEquals(habit.getCreatedAt(), habit.getUpdatedAt());
        assertEquals(habit.getCreatedAt(), LocalDateTime.parse(data.get("createdAt").asString()));
        assertEquals(data.get("createdAt"), data.get("updatedAt"));
        verify(habits).findByUserIdAndName(7L, "每日阅读");
    }

    /** 名称、描述上限允许保存；名称允许 emoji，当前长度按 UTF-16 单元计算。 */
    @Test
    void acceptsLengthBoundaries() throws Exception {
        check(send("POST", "", "owner", json.writeValueAsString(
                Map.of("name", "📚".repeat(25), "description", "文".repeat(200)))), 200, 0);
        verify(habits).insert(any());
    }

    /** 缺省或显式 null 保留 null，空字符串描述当前原样保存，不做归一化。 */
    @ParameterizedTest
    @ValueSource(strings = {"{\"name\":\"阅读\"}", "{\"name\":\"阅读\",\"description\":null}",
            "{\"name\":\"阅读\",\"description\":\"\"}"})
    void optionalDescription(String body) throws Exception {
        JsonNode data = check(send("POST", "", "owner", body), 200, 0).get("data");
        assertTrue(data.has("description"));
        if (body.contains("\"\"")) assertEquals("", data.get("description").asString());
        else assertTrue(data.get("description").isNull());
    }

    /** 非法 JSON、空白名称及服务端专属字段在业务写入前拒绝。 */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\"   \"}",
            "{\"name\":\"阅读\",\"userId\":8}", "{\"name\":\"阅读\",\"id\":1}",
            "{\"name\":\"阅读\"} {}", "{"})
    void invalidCreateDoesNotAccessMapper(String body) throws Exception {
        check(send("POST", "", "owner", body), 400, 40001);
        verifyNoInteractions(habits);
    }

    /** 锁定实际校验顺序：先检查原始字符串长度，再由业务层 trim。 */
    @Test
    void rejectsOverlongFieldsBeforeTrim() throws Exception {
        for (Map<String, String> body : List.of(Map.of("name", "文".repeat(51)),
                Map.of("name", " " + "文".repeat(50)), Map.of("name", "📚".repeat(26)),
                Map.of("name", "阅读", "description", "文".repeat(201)))) {
            check(send("POST", "", "owner", json.writeValueAsString(body)), 400, 40001);
        }
        verifyNoInteractions(habits);
    }

    /** 预检查发现同用户同名时直接冲突，不执行 INSERT。 */
    @Test
    void duplicatePrecheckDoesNotInsert() throws Exception {
        when(habits.findByUserIdAndName(7L, "阅读")).thenReturn(new Habit());
        check(send("POST", "", "owner", "{\"name\":\" 阅读 \"}"), 409, 40901);
        verify(habits, never()).insert(any());
    }

    /** 模拟预检查后发生唯一键竞争；验证异常分支，不冒充真实并发数据库测试。 */
    @Test
    void duplicateInsertIsConflict() throws Exception {
        doThrow(new DuplicateKeyException("uk_habits_user_name")).when(habits).insert(any());
        check(send("POST", "", "owner", "{\"name\":\"阅读\"}"), 409, 40901);
        verify(habits).insert(any());
    }

    /** 相同名称分别按两名用户查询和插入，不能错误地实施全局名称唯一。 */
    @Test
    void differentUsersMayCreateSameName() throws Exception {
        for (String token : List.of("owner", "other")) {
            check(send("POST", "", token, "{\"name\":\"阅读\"}"), 200, 0);
        }
        verify(habits).findByUserIdAndName(7L, "阅读");
        verify(habits).findByUserIdAndName(8L, "阅读");
        verify(habits).insert(argThat(h -> h.getUserId() == 7L));
        verify(habits).insert(argThat(h -> h.getUserId() == 8L));
    }

    /** 默认分页及空集合均为正常结果；伪造查询身份不能改变 Mapper 的用户条件。 */
    @Test
    void defaultEmptyPageUsesSessionUser() throws Exception {
        JsonNode data = check(send("GET", "?userId=999", "owner", null), 200, 0).get("data");
        assertEquals(0, data.get("items").size());
        assertEquals(0, data.get("total").asLong());
        assertEquals(1, data.get("page").asInt());
        assertEquals(20, data.get("pageSize").asInt());
        verify(habits).findByUserId(7L, 0L, 20);
        verify(habits).countByUserId(7L);
    }

    /** 列表映射保持 Mapper 顺序，包含更新时间，且响应不泄露持有者字段。 */
    @Test
    void mapsPageItemsAndTotal() throws Exception {
        Habit habit = new Habit();
        habit.setId(102L);
        habit.setUserId(8L);
        habit.setName("运动");
        habit.setCreatedAt(LocalDateTime.of(2026, 9, 19, 1, 0));
        habit.setUpdatedAt(habit.getCreatedAt());
        when(habits.findByUserId(8L, 2L, 2)).thenReturn(List.of(habit));
        when(habits.countByUserId(8L)).thenReturn(3L);
        JsonNode data = check(send("GET", "?page=2&pageSize=2", "other", null), 200, 0).get("data");
        assertEquals(3, data.get("total").asLong());
        assertEquals(2, data.get("page").asInt());
        assertEquals(2, data.get("pageSize").asInt());
        assertEquals(1, data.get("items").size());
        assertEquals("运动", data.get("items").get(0).get("name").asString());
        assertEquals(102, data.get("items").get(0).get("id").asLong());
        assertTrue(data.get("items").get(0).has("updatedAt"));
        assertFalse(data.get("items").get(0).has("userId"));
        verify(habits).countByUserId(8L);
        verify(habits).findByUserId(8L, 2L, 2);
    }

    /** 最大合法页码不会发生 int 乘法溢出；越过末页仍返回用户总数。 */
    @Test
    void largePageKeepsLongOffsetAndTotal() throws Exception {
        when(habits.countByUserId(7L)).thenReturn(3L);
        JsonNode data = check(send("GET", "?page=2147483647&pageSize=100", "owner", null), 200, 0).get("data");
        assertEquals(0, data.get("items").size());
        assertEquals(3, data.get("total").asLong());
        verify(habits).findByUserId(7L, 214748364600L, 100);
    }

    /** 负数、零、超限、溢出及无法绑定的分页参数都应返回 400，不能查询数据库。 */
    @ParameterizedTest
    @ValueSource(strings = {"page=0", "page=-1", "page=abc", "page=2147483648",
            "pageSize=0", "pageSize=-1", "pageSize=101", "pageSize=abc"})
    void invalidPagination(String query) throws Exception {
        check(send("GET", "?" + query, "owner", null), 400, 40001);
        verifyNoInteractions(habits);
    }

    /** 创建和列表均受生产拦截器保护，未认证时业务 Mapper 不应被调用。 */
    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void requiresAuthentication(String method) throws Exception {
        for (String token : new String[]{null, "expired"}) {
            check(send(method, "", token, method.equals("POST") ? "{\"name\":\"阅读\"}" : null), 401, 40101);
        }
        verifyNoInteractions(habits);
    }

    /** 插入阶段的数据库故障不能变成重名；对外隐藏原始数据库消息。 */
    @Test
    void insertFailuresKeepInfrastructureClassification() throws Exception {
        doThrow(new DataAccessResourceFailureException("private-db-detail")).when(habits).insert(any());
        check(send("POST", "", "owner", "{\"name\":\"阅读\"}"), 503, 50302);
        doThrow(new DataIntegrityViolationException("private-db-detail")).when(habits).insert(any());
        check(send("POST", "", "owner", "{\"name\":\"阅读\"}"), 500, 50001);
    }

    /** 分页查询或总数查询失败均不能返回不完整的成功分页。 */
    @Test
    void pageFailuresDoNotReturnPartialSuccess() throws Exception {
        when(habits.findByUserId(7L, 0L, 20)).thenThrow(new DataAccessResourceFailureException("private-db-detail"));
        check(send("GET", "", "owner", null), 503, 50302);
        doReturn(List.of()).when(habits).findByUserId(7L, 0L, 20);
        when(habits.countByUserId(7L)).thenThrow(new DataAccessResourceFailureException("private-db-detail"));
        check(send("GET", "", "owner", null), 503, 50302);
    }

    /** 通过随机端口发送请求，让真实 MVC 完成绑定、校验、序列化和错误处理。 */
    private HttpResponse<String> send(String method, String query, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/habits" + query))
                .timeout(Duration.ofSeconds(10));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 所有分支共同检查统一结构、禁止 HTTP 缓存及错误信息脱敏。 */
    private JsonNode check(HttpResponse<String> response, int status, int code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        JsonNode root = json.readTree(response.body());
        assertEquals(code, root.get("code").asInt());
        assertTrue(root.has("message"));
        assertTrue(root.has("data"));
        if (code != 0) assertTrue(root.get("data").isNull());
        assertFalse(response.body().contains("private-db-detail"));
        return root;
    }
}
