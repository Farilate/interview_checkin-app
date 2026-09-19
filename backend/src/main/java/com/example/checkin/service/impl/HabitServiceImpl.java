package com.example.checkin.service.impl;

import com.example.checkin.common.ErrorCode;
import com.example.checkin.dto.CreateHabitRequest;
import com.example.checkin.dto.HabitResponse;
import com.example.checkin.exception.BusinessException;
import com.example.checkin.mapper.HabitMapper;
import com.example.checkin.model.Habit;
import com.example.checkin.service.HabitService;
import com.example.checkin.dto.PageRequest;
import com.example.checkin.dto.PageResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.sql.SQLException;
import java.util.regex.Pattern;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * 打卡项业务实现。
 */
@Service
public class HabitServiceImpl implements HabitService {

    /**
     * MySQL 8 的错误键名可能带表名或库名前缀；只识别末尾完整键名，
     * 不能用 contains 匹配，以免将重复值里出现的索引名称误判为冲突来源。
     */
    private static final Pattern NAME_CONFLICT_KEY = Pattern.compile(
            "for key '(?:[^'.]+\\.)*uk_habits_user_name'\\s*$");

    private final HabitMapper habitMapper;

    public HabitServiceImpl(HabitMapper habitMapper) {
        this.habitMapper = habitMapper;
    }

    @Override
    public HabitResponse createHabit(
            long userId,
            CreateHabitRequest request) {

        // 长度校验必须在 trim 后执行；Service 再检查空白，保护非 HTTP 调用入口。
        String rawName = request.getName();
        if (rawName == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        String name = rawName.trim();
        if (name.isBlank() || name.codePointCount(0, name.length()) > 50) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        String description = request.getDescription();
        // 按 Unicode 码点而非 UTF-16 单元计数，一个补充平面 emoji 计为一个码点。
        if (description != null && description.codePointCount(0, description.length()) > 200) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        // 不裁剪有实际内容的描述，仅将缺省、空字符串或纯空白统一为数据库 NULL。
        if (description != null && description.isBlank()) {
            description = null;
        }

        // 先做一次友好的重复名称检查。
        if (habitMapper.findByUserIdAndName(userId, name) != null) {
            throw new BusinessException(
                    ErrorCode.HABIT_NAME_CONFLICT
            );
        }

        // 与 MySQL DATETIME(3) 的毫秒精度对齐，避免创建响应携带数据库无法保留的小数位。
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);

        Habit habit = new Habit();
        habit.setUserId(userId);
        habit.setName(name);
        habit.setDescription(description);
        habit.setCreatedAt(now);
        habit.setUpdatedAt(now);

        try {
            habitMapper.insert(habit);
        } catch (DuplicateKeyException e) {
            // 预检查不能阻止并发竞争；仅已确认的名称唯一约束冲突归为业务重名。
            if (isHabitNameConflict(e)) {
                throw new BusinessException(ErrorCode.HABIT_NAME_CONFLICT);
            }
            // 主键、其他索引或无法识别的冲突交给全局处理，不伪装成重名。
            throw e;
        }

        return toResponse(habit);
    }

    /**
     * 分页查询当前用户的打卡项。
     */
    @Override
    public PageResponse<HabitResponse> getHabits(
            long userId,
            PageRequest pageRequest) {

        List<Habit> habits = habitMapper.findByUserId(
                userId,
                pageRequest.offset(),
                pageRequest.getPageSize()
        );

        long total = habitMapper.countByUserId(userId);

        List<HabitResponse> items = habits.stream()
                .map(HabitServiceImpl::toResponse)
                .toList();

        return new PageResponse<>(
                items,
                total,
                pageRequest.getPage(),
                pageRequest.getPageSize()
        );
    }
    /** 创建与列表共用映射，持久层继续使用 UTC LocalDateTime，响应明确转换为时间点。 */
    private static HabitResponse toResponse(Habit habit) {
        return new HabitResponse(
                Long.toString(habit.getId()), habit.getName(), habit.getDescription(),
                habit.getCreatedAt().toInstant(ZoneOffset.UTC),
                habit.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }

    /**
     * 必须同时满足 MySQL 重复键错误号 1062、SQLState 23000 和明确的名称索引。
     * 只检查 JDBC 原始异常，不解析 Spring/MyBatis 包装消息中的 SQL 或用户输入。
     * 未知驱动消息格式保守返回 false，原异常由全局处理兜底，且不向客户端泄露原文。
     */
    private static boolean isHabitNameConflict(DuplicateKeyException exception) {
        Throwable cause = exception.getMostSpecificCause();
        return cause instanceof SQLException sql
                && sql.getErrorCode() == 1062
                && "23000".equals(sql.getSQLState())
                && sql.getMessage() != null
                && NAME_CONFLICT_KEY.matcher(sql.getMessage()).find();
    }
}
