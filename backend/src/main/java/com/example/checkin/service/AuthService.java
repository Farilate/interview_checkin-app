package com.example.checkin.service;

import com.example.checkin.dto.LoginRequest;
import com.example.checkin.dto.LoginResponse;

public interface AuthService {

    LoginResponse login(LoginRequest request);
}