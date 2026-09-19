-- 可选演示数据：仅在本地或隔离演示数据库中手工执行，不由应用启动加载。
-- 先执行 init.sql 并明确选中数据库；公开演示凭证见 README，不得用于生产环境。
-- 已存在 demo_user 时跳过，不覆盖已有账户密码；本脚本不承担并发注册职责。
-- 数据库只写入 BCrypt 哈希，时间使用 UTC；不创建习惯或打卡记录。
INSERT INTO users (username, password_hash, created_at, updated_at)
SELECT 'demo_user', '$2a$10$EywWUrlFoz/94/99uUzmCOxM2KwA7gJx/dFjNVBs5zaCRdutJZjrK',
       UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'demo_user');
