package com.example.checkin.service;

public interface SessionService {

    String createSession(long userId);

    Long getUserId(String token);

    void deleteSession(String token);
}