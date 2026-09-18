-- 数据库初始化脚本：先明确选中一个空数据库，再执行下面的建表语句，且只执行一次。
-- 本脚本不删除已有表、不插入演示数据；时间戳由应用按协调世界时（UTC）显式写入。
--
-- 首次手动初始化时，可单独执行以下建库和选库示例，数据库名可按实际环境修改：
-- CREATE DATABASE checkin CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
-- USE checkin;
--
-- 建库和选库语句保留为注释，避免集成测试执行本脚本时切换到固定的业务数据库。
-- 集成测试应连接专用空测试库，由连接地址指定库名，再执行同一份建表脚本。
-- 建库需要相应权限；已有数据库时无需重复建库，但执行前必须确认所选库为空。

-- 用户表：用户名采用二进制排序规则精确比较，小写规范化由业务层负责。
-- 密码字段只存储编码后的哈希，不保存明文密码或登录令牌。
CREATE TABLE users (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    -- 数据库唯一约束防止重复用户名。
    UNIQUE KEY uk_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 习惯表：不同用户允许同名；同一用户内习惯名称必须唯一。
CREATE TABLE habits (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(50) NOT NULL,
    description VARCHAR(200) NULL DEFAULT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    -- 同一用户下不允许存在两个相同名称的习惯。
    UNIQUE KEY uk_habits_user_name (user_id, name),
    -- 为打卡记录的复合外键提供唯一的用户与习惯组合。
    UNIQUE KEY uk_habits_user_id_id (user_id, id),
    -- 支持按用户查询，并按创建时间、主键进行稳定排序和分页。
    KEY idx_habits_user_created (user_id, created_at, id),
    CONSTRAINT fk_habits_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 打卡记录表：业务日期按上海时区确定，实际打卡时间按协调世界时写入。
CREATE TABLE checkin_records (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    habit_id BIGINT UNSIGNED NOT NULL,
    checkin_date DATE NOT NULL,
    checked_in_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    -- 并发安全的最终保障：同一用户、同一习惯、同一天最多一条记录。
    UNIQUE KEY uk_checkin_user_habit_date (user_id, habit_id, checkin_date),
    -- 同时约束习惯存在且属于该用户；禁止级联删除或修改关联键，保护历史记录。
    CONSTRAINT fk_checkin_habit_owner FOREIGN KEY (user_id, habit_id)
        REFERENCES habits (user_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
