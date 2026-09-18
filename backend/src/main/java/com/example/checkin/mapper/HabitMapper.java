package com.example.checkin.mapper;

import com.example.checkin.model.Habit;
import org.apache.ibatis.annotations.*;
import java.util.List;

/**
 * 习惯表访问接口。所有读取都带 userId 条件，限制查询范围。
 * userId 必须由后续业务层从服务端登录身份获取；Mapper 本身不执行认证。
 * #{...} 绑定参数，@Param 声明多参数方法中 SQL 使用的参数名。
 */
@Mapper
public interface HabitMapper {
    /**
     * 插入习惯并回填 habit.id；返回受影响行数。
     * 同一用户下习惯名称必须唯一，数据库唯一约束负责最终并发兜底。
     */
    @Insert("""
            INSERT INTO habits (user_id, name, description, created_at, updated_at)
            VALUES (#{userId}, #{name}, #{description}, #{createdAt}, #{updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Habit habit);

    /** 同时按所有者和主键查找；不存在或属于其他用户时均返回 null。 */
    @Select("""
            SELECT id, user_id, name, description, created_at, updated_at FROM habits
            WHERE user_id = #{userId} AND id = #{habitId}
            """)
    Habit findByUserIdAndId(@Param("userId") long userId, @Param("habitId") long habitId);

    /**
     * 按当前用户和习惯名称查询。
     * 用于业务层在创建前进行友好的重复名称检查。
     * 最终唯一性仍由数据库 uk_habits_user_name 约束保证。
     */
    @Select("""
        SELECT id, user_id, name, description, created_at, updated_at
        FROM habits
        WHERE user_id = #{userId} AND name = #{name}
        LIMIT 1
        """)
    Habit findByUserIdAndName(@Param("userId") long userId,
                              @Param("name") String name);

    /**
     * 分页读取当前用户的习惯，创建时间相同则按 ID 倒序，保证排序稳定。
     * offset 是跳过行数而非页码；调用方须校验 offset 非负、limit 合法并限制页大小。
     * 没有匹配记录时返回空列表。
     */
    @Select("""
            SELECT id, user_id, name, description, created_at, updated_at FROM habits
            WHERE user_id = #{userId} ORDER BY created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<Habit> findByUserId(@Param("userId") long userId, @Param("offset") long offset,
                           @Param("limit") int limit);

    /** 查询该用户的习惯总数，供后续分页响应使用，不是打卡次数或连续天数。 */
    @Select("SELECT COUNT(*) FROM habits WHERE user_id = #{userId}")
    long countByUserId(@Param("userId") long userId);
}
