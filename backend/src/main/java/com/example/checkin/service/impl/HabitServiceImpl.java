package com.example.checkin.service.impl;

import com.example.checkin.common.ErrorCode;
import com.example.checkin.dto.CreateHabitRequest;
import com.example.checkin.dto.HabitResponse;
import com.example.checkin.exception.BusinessException;
import com.example.checkin.mapper.HabitMapper;
import com.example.checkin.model.Habit;
import com.example.checkin.service.HabitService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 打卡项业务实现。
 */
@Service
public class HabitServiceImpl implements HabitService {

    private final HabitMapper habitMapper;

    public HabitServiceImpl(HabitMapper habitMapper) {
        this.habitMapper = habitMapper;
    }

    @Override
    public HabitResponse createHabit(
            long userId,
            CreateHabitRequest request) {

        String name = request.getName().trim();

        // 先做一次友好的重复名称检查。
        if (habitMapper.findByUserIdAndName(userId, name) != null) {
            throw new BusinessException(
                    ErrorCode.HABIT_NAME_CONFLICT
            );
        }

        LocalDateTime now =
                LocalDateTime.now(ZoneOffset.UTC);

        Habit habit = new Habit();
        habit.setUserId(userId);
        habit.setName(name);
        habit.setDescription(request.getDescription());
        habit.setCreatedAt(now);
        habit.setUpdatedAt(now);

        try {
            habitMapper.insert(habit);
        } catch (DuplicateKeyException e) {
            // 防止并发请求同时通过前面的查询检查。
            throw new BusinessException(
                    ErrorCode.HABIT_NAME_CONFLICT
            );
        }

        return new HabitResponse(
                habit.getId(),
                habit.getName(),
                habit.getDescription(),
                habit.getCreatedAt(),
                habit.getUpdatedAt()
        );
    }
}