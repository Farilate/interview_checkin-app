package com.example.checkin;

import com.example.checkin.config.PasswordConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 检查公开演示 SQL 的 BCrypt 哈希可由生产编码器验证，不实际执行数据库写入。 */
class DemoDataTest {
    /** 防止 SQL 中的哈希与 README 公布的演示密码不一致，导致用户无法按文档登录。 */
    @Test
    void documentedDemoPasswordMatchesSeedHash() throws Exception {
        String seed = Files.readString(Path.of("../database/demo-data.sql"));
        var hash = Pattern.compile("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}").matcher(seed);
        assertTrue(hash.find(), "演示 SQL 应包含 BCrypt 哈希");
        var encoder = new PasswordConfig().passwordEncoder();
        assertTrue(encoder.matches("DemoOnly123!", hash.group()));
        assertFalse(encoder.matches("wrong-password", hash.group()));
        assertFalse(seed.contains("DemoOnly123!"), "SQL 只保存哈希，不保存明文密码");
    }
}
