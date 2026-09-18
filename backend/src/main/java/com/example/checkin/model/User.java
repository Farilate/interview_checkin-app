package com.example.checkin.model;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户表 users 的持久化对象；包含密码哈希，不能直接作为对外响应。
 * Lombok 在编译时生成标准 JavaBean getter/setter，供 MyBatis 读取参数、映射结果和回填主键。
 * 保留 Java 默认无参构造；只生成访问方法，避免自动输出全部字段或改变对象相等语义。
 * 本对象只承载数据，不执行字段校验或业务计算。
 */
@Getter
@Setter
public class User {
    /** 数据库自增主键：插入前可为 null，由 MyBatis 在插入成功后回填。 */
    private Long id;

    /** 用户名；业务约定为小写 ASCII，由业务层负责规范化，数据库保证唯一。 */
    private String username;

    /** BCrypt 编码后的密码哈希；禁止保存明文密码或输出到接口、日志。 */
    private String passwordHash;

    /** 创建时间，按 UTC 约定填入；LocalDateTime 本身不携带时区。 */
    private LocalDateTime createdAt;

    /** 最后更新时间，由调用方显式维护，按 UTC 约定填入。 */
    private LocalDateTime updatedAt;
}
