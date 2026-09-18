package com.example.checkin;

import com.example.checkin.auth.AuthCredentials;
import com.example.checkin.auth.AuthInterceptor;
import com.example.checkin.common.ErrorCode;
import com.example.checkin.dto.CurrentUserResponse;
import com.example.checkin.dto.LoginRequest;
import com.example.checkin.exception.BusinessException;
import com.example.checkin.mapper.UserMapper;
import com.example.checkin.model.User;
import com.example.checkin.service.AuthService;
import com.example.checkin.service.SessionService;
import com.example.checkin.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 当前认证模块的边界回归；存储使用测试替身，不替代真实 MySQL/Redis 联调。 */
class AuthRepairTest {
    /** UTF-8 字节数不能用 Java 字符数代替，用户名规则在规范化前限制为 ASCII。 */
    @Test
    void credentialBoundaries() {
        assertEquals("demo_user", AuthCredentials.normalize(" DEMO_User ", "密".repeat(24)));
        assertEquals("abc", AuthCredentials.normalize("abc", " 123456 "));
        assertEquals("a".repeat(32), AuthCredentials.normalize("a".repeat(32), "x".repeat(72)));
        for (String password : new String[]{"short", "密".repeat(25), "x".repeat(73), "        "}) {
            assertCode(ErrorCode.INVALID_ARGUMENT, () -> AuthCredentials.normalize("demo", password));
        }
        for (String username : new String[]{"ab", "a".repeat(33), "中文用户", "abc-", "İabc"}) {
            assertCode(ErrorCode.INVALID_ARGUMENT, () -> AuthCredentials.normalize(username, "password"));
        }
        assertCode(ErrorCode.INVALID_ARGUMENT, () -> AuthCredentials.normalize(null, "password"));
        assertCode(ErrorCode.INVALID_ARGUMENT, () -> AuthCredentials.normalize("demo", null));
    }

    /** 使用真实 BCrypt 验证密码空格不被裁剪，完整响应中大整数 ID 必须为 JSON 字符串。 */
    @Test
    void loginContractAndPasswordVerification() {
        UserMapper mapper = mock(UserMapper.class);
        SessionService sessions = mock(SessionService.class);
        var encoder = new BCryptPasswordEncoder(4);
        var service = new AuthServiceImpl(mapper, encoder, sessions);
        User user = new User();
        user.setId(9007199254740993L);
        user.setUsername("demo");
        user.setPasswordHash(encoder.encode(" password "));
        when(mapper.findByUsername("demo")).thenReturn(user);
        when(sessions.createSession(user.getId())).thenReturn("test-token");
        when(sessions.getTtlSeconds()).thenReturn(123L);
        LoginRequest request = new LoginRequest();
        request.setUsername(" DEMO ");
        request.setPassword(" password ");
        var json = JsonMapper.builder().build().valueToTree(service.login(request));
        assertEquals("Bearer", json.get("tokenType").asString());
        assertEquals(123, json.get("expiresIn").asInt());
        assertEquals("test-token", json.get("token").asString());
        assertTrue(json.get("user").get("id").isString());
        assertEquals("9007199254740993", json.get("user").get("id").asString());
        assertFalse(json.toString().contains("passwordHash"));
        request.setPassword("password");
        assertCode(ErrorCode.INVALID_CREDENTIALS, () -> service.login(request));
        request.setUsername("absent");
        assertCode(ErrorCode.INVALID_CREDENTIALS, () -> service.login(request));
        verify(sessions, times(1)).createSession(anyLong());
    }

    /** 无效输入必须在数据库查询和会话创建之前拒绝。 */
    @Test
    void invalidLoginDoesNotAccessStorage() {
        UserMapper mapper = mock(UserMapper.class);
        SessionService sessions = mock(SessionService.class);
        var service = new AuthServiceImpl(mapper, new BCryptPasswordEncoder(4), sessions);
        LoginRequest request = new LoginRequest();
        request.setUsername("ab");
        request.setPassword("password");
        assertCode(ErrorCode.INVALID_ARGUMENT, () -> service.login(request));
        verifyNoInteractions(mapper, sessions);
    }

    /** 缺少、空白和格式错误的令牌均不访问存储。 */
    @ParameterizedTest
    @ValueSource(strings = {"", "Basic abc", "Bearer ", "Bearer"})
    void invalidHeader(String header) {
        var sessions = mock(SessionService.class);
        var auth = mock(AuthService.class);
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", header);
        assertCode(ErrorCode.UNAUTHORIZED, () -> new AuthInterceptor(sessions, auth)
                .preHandle(request, new MockHttpServletResponse(), new Object()));
        verifyNoInteractions(sessions, auth);
    }

    /** 不能用仍未到期的 Redis 会话访问已删除的账户。 */
    @Test
    void deletedUserSessionIsRevoked() {
        var sessions = mock(SessionService.class);
        var auth = mock(AuthService.class);
        when(sessions.getUserId("token")).thenReturn(7L);
        when(auth.getCurrentUser(7L)).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        assertCode(ErrorCode.UNAUTHORIZED, () -> new AuthInterceptor(sessions, auth)
                .preHandle(request, new MockHttpServletResponse(), new Object()));
        verify(sessions).deleteSession("token");
        assertNull(request.getAttribute(AuthInterceptor.CURRENT_USER_ID));
    }

    /** 身份只能来自会话；数据库故障不应撤销有效会话。 */
    @Test
    void identityAndDatabaseFailure() {
        var sessions = mock(SessionService.class);
        var auth = mock(AuthService.class);
        when(sessions.getUserId("token")).thenReturn(7L);
        when(auth.getCurrentUser(7L)).thenReturn(new CurrentUserResponse("7", "demo"));
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        request.addParameter("userId", "99");
        var interceptor = new AuthInterceptor(sessions, auth);
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals(7L, request.getAttribute(AuthInterceptor.CURRENT_USER_ID));
        when(auth.getCurrentUser(7L)).thenThrow(new BusinessException(ErrorCode.DATABASE_UNAVAILABLE));
        assertCode(ErrorCode.DATABASE_UNAVAILABLE, () -> interceptor.preHandle(
                request, new MockHttpServletResponse(), new Object()));
        verify(sessions, never()).deleteSession(anyString());
    }

    /** 统一检查业务异常的分类，避免仅断言抛出异常却漏掉错误码。 */
    private static void assertCode(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(BusinessException.class, action).getErrorCode());
    }
}
