package com.example.checkin.model;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 习惯表 habits 的持久化对象；userId 表示数据所属用户。
 * Lombok 在编译时生成标准 JavaBean getter/setter，供 MyBatis 读取参数、映射结果和回填主键。
 * 保留 Java 默认无参构造；只生成访问方法，避免自动输出全部字段或改变对象相等语义。
 * 本对象只承载数据，不执行字段校验或业务计算。
 */
@Getter
@Setter
public class Habit {
    /** 数据库自增主键：插入前可为 null，由 MyBatis 在插入成功后回填。 */
    private Long id;

    /** 所属用户 ID；业务调用方须从服务端身份确定，不能直接信任前端输入。 */
    private Long userId;

    /** 习惯名称；业务层须先 trim 并校验非空及长度，不同用户允许同名，同一用户内名称唯一。 */
    private String name;

    /** 可选描述，对应可空列，未提供时为 null。 */
    private String description;

    /** 创建时间，按 UTC 约定填入；LocalDateTime 本身不携带时区。 */
    private LocalDateTime createdAt;

    /** 最后更新时间，由调用方显式维护，按 UTC 约定填入。 */
    private LocalDateTime updatedAt;
}
