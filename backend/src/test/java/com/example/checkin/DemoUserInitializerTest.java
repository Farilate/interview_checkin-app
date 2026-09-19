package com.example.checkin;

import com.example.checkin.common.ErrorCode;
import com.example.checkin.config.DemoUserInitializer;
import com.example.checkin.exception.BusinessException;
import com.example.checkin.mapper.UserMapper;
import com.example.checkin.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 演示账户初始化回归；模拟数据库竞态分支，不声称替代真实多实例并发验收。 */
class DemoUserInitializerTest {
    /** 配置不完整时不能查询或写入数据库，也不执行密码编码。 */
    @ParameterizedTest
    @CsvSource(value = {"'','password'", "'demo',''", "' ','password'", "'demo',' '"}, ignoreLeadingAndTrailingWhitespace = false)
    void missingCredentialsSkip(String username, String password) {
        var users = mock(UserMapper.class);
        var encoder = mock(PasswordEncoder.class);
        new DemoUserInitializer(users, encoder, username, password).run();
        verifyNoInteractions(users, encoder);
    }

    /** 创建数据包含规范化用户名、可验证的 BCrypt 和同一 UTC 时间，密码空格必须保留。 */
    @Test
    void createsSafeAccount() {
        var users = mock(UserMapper.class);
        var encoder = new BCryptPasswordEncoder(4);
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
        new DemoUserInitializer(users, encoder, " DEMO ", " password ").run();
        LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC);
        var saved = ArgumentCaptor.forClass(User.class);
        verify(users).insert(saved.capture());
        User user = saved.getValue();
        assertEquals("demo", user.getUsername());
        assertNotEquals(" password ", user.getPasswordHash());
        assertTrue(encoder.matches(" password ", user.getPasswordHash()));
        assertFalse(encoder.matches("password", user.getPasswordHash()));
        assertEquals(user.getCreatedAt(), user.getUpdatedAt());
        assertFalse(user.getCreatedAt().isBefore(before));
        assertFalse(user.getCreatedAt().isAfter(after));
    }

    /** 已存在账户不得重新编码或覆盖密码。 */
    @Test
    void existingUserIsUnchanged() {
        var users = mock(UserMapper.class);
        var encoder = mock(PasswordEncoder.class);
        var existing = new User();
        existing.setPasswordHash("original-hash");
        when(users.findByUsername("demo")).thenReturn(existing);
        new DemoUserInitializer(users, encoder, "DEMO", "password").run();
        verify(users, never()).insert(any());
        verifyNoInteractions(encoder);
        assertEquals("original-hash", existing.getPasswordHash());
    }

    /** 不合法凭证必须在存储访问之前拒绝，与正式登录规则一致。 */
    @Test
    void invalidCredentialsFailBeforeStorage() {
        var users = mock(UserMapper.class);
        var encoder = mock(PasswordEncoder.class);
        var initializer = new DemoUserInitializer(users, encoder, "ab", "password");
        assertEquals(ErrorCode.INVALID_ARGUMENT,
                assertThrows(BusinessException.class, initializer::run).getErrorCode());
        verifyNoInteractions(users, encoder);
    }

    /** 插入冲突后能够查询到目标账户时，视为其他实例已完成初始化。 */
    @Test
    void concurrentWinnerAllowsStartup() {
        var users = mock(UserMapper.class);
        when(users.findByUsername("demo")).thenReturn(null).thenReturn(new User());
        doThrow(new DuplicateKeyException("测试用户名冲突")).when(users).insert(any());
        assertDoesNotThrow(() -> new DemoUserInitializer(users, new BCryptPasswordEncoder(4), "demo", "password").run());
        var order = inOrder(users);
        order.verify(users).findByUsername("demo");
        order.verify(users).insert(any());
        order.verify(users).findByUsername("demo");
        verifyNoMoreInteractions(users);
    }

    /** 无法用目标账户已存在解释冲突时，必须保留原异常。 */
    @Test
    void unexplainedDuplicateIsRethrown() {
        var users = mock(UserMapper.class);
        var failure = new DuplicateKeyException("测试其他唯一键冲突");
        doThrow(failure).when(users).insert(any());
        assertSame(failure, assertThrows(DuplicateKeyException.class,
                () -> new DemoUserInitializer(users, new BCryptPasswordEncoder(4), "demo", "password").run()));
        verify(users, times(2)).findByUsername("demo");
    }

    /** 普通数据库故障不能伪装成并发初始化成功或触发唯一冲突重查。 */
    @Test
    void databaseFailureIsRethrown() {
        var users = mock(UserMapper.class);
        var failure = new DataAccessResourceFailureException("测试连接失败");
        doThrow(failure).when(users).insert(any());
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> new DemoUserInitializer(users, new BCryptPasswordEncoder(4), "demo", "password").run()));
        verify(users, times(1)).findByUsername("demo");
    }

    /** 冲突后的重查本身失败也必须使启动失败，不能当作目标用户已存在。 */
    @Test
    void recheckFailureIsNotHidden() {
        var users = mock(UserMapper.class);
        var failure = new DataAccessResourceFailureException("测试重查失败");
        when(users.findByUsername("demo")).thenReturn(null).thenThrow(failure);
        doThrow(new DuplicateKeyException("测试冲突")).when(users).insert(any());
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> new DemoUserInitializer(users, new BCryptPasswordEncoder(4), "demo", "password").run()));
    }
}
