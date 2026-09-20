package com.example.checkin.controller;

import com.example.checkin.auth.AuthInterceptor;
import com.example.checkin.common.ApiResponse;
import com.example.checkin.dto.CreateHabitRequest;
import com.example.checkin.dto.HabitResponse;
import com.example.checkin.service.HabitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;
import com.example.checkin.dto.PageRequest;
import com.example.checkin.dto.PageResponse;
import org.springframework.http.HttpStatus;
import com.example.checkin.dto.CheckinResponse;
import com.example.checkin.service.CheckinRecordService;
import org.springframework.web.bind.annotation.PathVariable;
import com.example.checkin.dto.StreakResponse;
import com.example.checkin.dto.TodayCheckinStatusResponse;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * 打卡项接口。
 *
 * <p>当前用户身份统一由 AuthInterceptor 根据登录 Token 解析，
 * 客户端不能自行指定 userId。
 * <p>habitId 由 MVC 校验为正整数；范围非法返回 40001，合法但无权访问或不存在返回 40401。
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
     * <p>接口具备幂等性：
     * 第一次请求创建记录；
     * 同一天重复请求返回已有记录。
     */
    @PutMapping("/{habitId}/checkins/today")
    public ApiResponse<CheckinResponse> checkIn(
            @PathVariable @Positive long habitId,
            HttpServletRequest request) {

        Long userId = (Long) request.getAttribute(
                AuthInterceptor.CURRENT_USER_ID
        );

        return ApiResponse.ok(
                checkinRecordService.checkIn(userId, habitId)
        );
    }

    /**
     * 查询指定打卡项的当前连续打卡天数。
     */
    @GetMapping("/{habitId}/streak")
    public ApiResponse<StreakResponse> getStreak(
            @PathVariable @Positive long habitId,
            HttpServletRequest request) {

        Long userId = (Long) request.getAttribute(
                AuthInterceptor.CURRENT_USER_ID
        );

        int streak = checkinRecordService.getCurrentStreak(
                userId,
                habitId
        );

        return ApiResponse.ok(
                new StreakResponse(streak)
        );
    }

    /**
     * 查询指定打卡项今天是否已经打卡。
     */
    @GetMapping("/{habitId}/checkins/today")
    public ApiResponse<TodayCheckinStatusResponse> getTodayCheckinStatus(
            @PathVariable @Positive long habitId,
            HttpServletRequest request) {

        Long userId = (Long) request.getAttribute(
                AuthInterceptor.CURRENT_USER_ID
        );

        boolean checkedIn =
                checkinRecordService.hasCheckedInToday(
                        userId,
                        habitId
                );

        return ApiResponse.ok(
                new TodayCheckinStatusResponse(checkedIn)
        );
    }
}
