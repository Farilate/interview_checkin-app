package com.example.checkin.controller;

import com.example.checkin.auth.AuthInterceptor;
import com.example.checkin.common.ApiResponse;
import com.example.checkin.dto.CreateHabitRequest;
import com.example.checkin.dto.HabitResponse;
import com.example.checkin.service.HabitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 打卡项接口。
 *
 * <p>当前用户身份统一由 AuthInterceptor 根据登录 Token 解析，
 * 客户端不能自行指定 userId。
 */
@RestController
@RequestMapping("/api/v1/habits")
public class HabitController {

    private final HabitService habitService;

    public HabitController(HabitService habitService) {
        this.habitService = habitService;
    }

    /**
     * 创建新的打卡项。
     */
    @PostMapping
    public ApiResponse<HabitResponse> createHabit(
            @Valid @RequestBody CreateHabitRequest requestBody,
            HttpServletRequest request) {

        Long userId = (Long) request.getAttribute(
                AuthInterceptor.CURRENT_USER_ID
        );

        return ApiResponse.ok(
                habitService.createHabit(userId, requestBody)
        );
    }
}