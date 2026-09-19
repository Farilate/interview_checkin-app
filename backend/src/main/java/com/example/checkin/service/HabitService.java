package com.example.checkin.service;

import com.example.checkin.dto.CreateHabitRequest;
import com.example.checkin.dto.HabitResponse;
import com.example.checkin.dto.PageRequest;
import com.example.checkin.dto.PageResponse;

public interface HabitService {

    /**
     * 创建当前用户的打卡项。
     */
    HabitResponse createHabit(
            long userId,
            CreateHabitRequest request
    );

    /**
     * 分页查询当前用户的打卡项。
     */
    PageResponse<HabitResponse> getHabits(
            long userId,
            PageRequest pageRequest
    );
}