package com.example.checkin.service;

import com.example.checkin.dto.CreateHabitRequest;
import com.example.checkin.dto.HabitResponse;

public interface HabitService {

    HabitResponse createHabit(
            long userId,
            CreateHabitRequest request
    );
}