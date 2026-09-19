package com.example.checkin.service;

import com.example.checkin.dto.CheckinResponse;

public interface CheckinRecordService {

    CheckinResponse checkIn(long userId, long habitId);
}