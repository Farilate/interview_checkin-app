package com.example.checkin;

import com.example.checkin.auth.AuthInterceptor;
import com.example.checkin.config.ApiResponseAdvice;
import com.example.checkin.controller.AuthController;
import com.example.checkin.exception.GlobalExceptionHandler;
import com.example.checkin.mapper.UserMapper;
import com.example.checkin.model.User;
import com.example.checkin.service.impl.AuthServiceImpl;
import com.example.checkin.service.impl.SessionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MVC 认证回归：使用生产 Controller、Service、拦截器和异常处理，仅替换外部存储。 */
class AuthControllerTest {
    private MockMvc mvc;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private UserMapper users;

    /** 不启动应用初始化器，不连接开发数据库；测试替身仅模拟独立会话键的读写删除。 */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        users = mock(UserMapper.class);
        when(redis.opsForValue()).thenReturn(values);
        var stored = new HashMap<String, String>();
        doAnswer(call -> { stored.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.get(anyString())).thenAnswer(call -> stored.get(call.<String>getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(call -> stored.remove(call.<String>getArgument(0)) != null);
        var encoder = new BCryptPasswordEncoder(4);
        var user = new User();
        user.setId(7L);
        user.setUsername("demo");
        user.setPasswordHash(encoder.encode("password"));
        when(users.findByUsername("demo")).thenReturn(user);
        when(users.findById(7L)).thenReturn(user);
        var sessions = new SessionServiceImpl(redis, 123);
        var auth = new AuthServiceImpl(users, encoder, sessions);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth))
                .setControllerAdvice(new GlobalExceptionHandler(), new ApiResponseAdvice())
                .addMappedInterceptors(new String[]{"/api/v1/auth/me", "/api/v1/auth/logout"},
                        new AuthInterceptor(sessions, auth)).build();
    }

    /** 登录响应、当前用户、登出、重复登出及另一会话仍有效必须共同成立。 */
    @Test
    void loginMeLogoutAndSessionIsolation() throws Exception {
        String first = login();
        String second = login();
        assertNotEquals(first, second);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + first))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("7"));
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + first))
                .andExpect(status().isOk()).andExpect(content().json("{\"code\":0,\"message\":\"ok\",\"data\":null}"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + first))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(40101));
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + first))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + second))
                .andExpect(status().isOk());
    }

    /** 无登录信息和非法凭证输入须通过统一 JSON 错误响应返回。 */
    @Test
    void invalidRequests() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content("{\"username\":\"demo\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
    }

    /** 三种 Redis 操作失败都必须返回会话错误码，不落入数据库错误分类。 */
    @Test
    void sessionFailuresUse50301() throws Exception {
        String token = login();
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("测试删除失败"));
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50301));
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("测试读取失败"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50301));
        doThrow(new RedisConnectionFailureException("测试写入失败"))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content("{\"username\":\"demo\",\"password\":\"password\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50301));
    }

    /** 用户删除后第一次请求清理会话，第二次直接拒绝过期身份。 */
    @Test
    void deletedUserCannotKeepSession() throws Exception {
        String token = login();
        when(users.findById(7L)).thenReturn(null);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        verify(users, times(1)).findById(7L);
        verify(redis).delete(anyString());
    }

    /** 用户已删除但会话清理失败时返回 50301，不误报成功或掩盖存储故障。 */
    @Test
    void deletedUserCleanupFailure() throws Exception {
        String token = login();
        when(users.findById(7L)).thenReturn(null);
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("测试清理失败"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50301));
    }

    /** 鉴权阶段 MySQL 暂时不可用不能删除正常会话，恢复后原令牌仍可访问。 */
    @Test
    void databaseFailurePreservesSession() throws Exception {
        String token = login();
        User restored = new User();
        restored.setId(7L);
        restored.setUsername("demo");
        when(users.findById(7L)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("测试数据库故障"))
                .thenReturn(restored);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50302));
        verify(redis, never()).delete(anyString());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    /** 无效或模拟已过期令牌必须在查询用户表前拒绝。真实自然到期另行集成验收。 */
    @Test
    void unknownTokenDoesNotQueryUser() throws Exception {
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer unknown-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(40101));
        verifyNoInteractions(users);
    }

    /** 合法格式的错误密码与不存在账户返回相同错误，不创建任何会话。 */
    @Test
    void wrongCredentialsDoNotCreateSession() throws Exception {
        for (String username : new String[]{"demo", "absent"}) {
            mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                            .content("{\"username\":\"" + username + "\",\"password\":\"wrong-password\"}"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(40102))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        verifyNoInteractions(values);
    }

    /** 返回测试令牌并核对完整登录响应，不将令牌打印到控制台。 */
    private String login() throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content("{\"username\":\"demo\",\"password\":\"password\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(123))
                .andExpect(jsonPath("$.data.user.id").value("7"))
                .andExpect(jsonPath("$.data.user.passwordHash").doesNotExist()).andReturn();
        return JsonMapper.builder().build().readTree(result.getResponse().getContentAsString())
                .get("data").get("token").asString();
    }
}
