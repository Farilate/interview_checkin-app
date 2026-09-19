package com.example.checkin.controller;

import com.example.checkin.auth.AuthInterceptor;
import com.example.checkin.common.ApiResponse;
import com.example.checkin.dto.CreateHabitRequest;
import com.example.checkin.dto.HabitResponse;
import com.example.checkin.service.HabitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.example.checkin.dto.PageRequest;
import com.example.checkin.dto.PageResponse;
import org.springframework.http.HttpStatus;
import com.example.checkin.dto.CheckinResponse;
import com.example.checkin.service.CheckinRecordService;
import org.springframework.web.bind.annotation.PathVariable;

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
    private final CheckinRecordService checkinRecordService;

    public HabitController(
            HabitService habitService,
            CheckinRecordService checkinRecordService) {
        this.habitService = habitService;
        this.checkinRecordService = checkinRecordService;
    }
    /**
     * 创建新的打卡项，写入成功返回 201 Created；错误仍由统一异常处理决定状态。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
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

    /** 分页查询当前会话所属用户的数据，成功仍返回 200 OK。 */
    @GetMapping
    public ApiResponse<PageResponse<HabitResponse>> getHabits(
            @Valid @ModelAttribute PageRequest pageRequest,
            HttpServletRequest request) {

        Long userId = (Long) request.getAttribute(
                AuthInterceptor.CURRENT_USER_ID
        );

        return ApiResponse.ok(
                habitService.getHabits(userId, pageRequest)
        );
    }

    /**
     * 对指定打卡项执行今日打卡。
     *
     * <p>当前用户 ID 来自服务端认证结果，
     * habitId 来自请求路径。
     * 同一天重复请求返回已有打卡记录，保证接口幂等。
     */
    @PostMapping("/{habitId}/checkins")
    public ApiResponse<CheckinResponse> checkIn(
            @PathVariable long habitId,
            HttpServletRequest request) {

        Long userId = (Long) request.getAttribute(
                AuthInterceptor.CURRENT_USER_ID
        );

        return ApiResponse.ok(
                checkinRecordService.checkIn(userId, habitId)
        );
    }
}
