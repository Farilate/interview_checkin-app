package com.example.checkin.controller;

import com.example.checkin.common.ApiResponse;
import com.example.checkin.dto.LoginRequest;
import com.example.checkin.dto.LoginResponse;
import com.example.checkin.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request) {

        return ApiResponse.ok(authService.login(request));
    }
}