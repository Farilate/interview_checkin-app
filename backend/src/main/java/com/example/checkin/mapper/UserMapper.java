package com.example.checkin.mapper;

import com.example.checkin.model.User;
import org.apache.ibatis.annotations.*;

/**
 * 用户表访问接口，由 MyBatis 创建代理实现。
 * SQL 中的 #{...} 使用参数绑定；下划线列名通过全局配置映射为 Java 驼峰属性。
 * 返回的 User 含密码哈希，后续接口必须转换为专用响应 DTO，不能直接返回该对象。
 */
@Mapper
public interface UserMapper {
    /**
     * 插入用户，返回受影响行数（成功时为 1），自增主键回填到 user.id。
     * 调用方负责用户名规范化、密码哈希和 UTC 时间；重复用户名由数据库唯一约束拒绝。
     */
    @Insert("""
            INSERT INTO users (username, password_hash, created_at, updated_at)
            VALUES (#{username}, #{passwordHash}, #{createdAt}, #{updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    /** 按主键读取用户；不存在时返回 null，不负责登录身份认证。 */
    @Select("SELECT id, username, password_hash, created_at, updated_at FROM users WHERE id = #{id}")
    User findById(@Param("id") long id);

    /** 精确查找用户名；调用前须按业务约定转为小写，不存在时返回 null。 */
    @Select("SELECT id, username, password_hash, created_at, updated_at FROM users WHERE username = #{username}")
    User findByUsername(@Param("username") String username);
}
