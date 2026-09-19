package com.example.checkin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 业务时间配置。
 *
 * <p>打卡业务日期按照配置的业务时区确定，
 * 默认使用 Asia/Shanghai。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock businessClock(
            @Value("${app.business-zone:Asia/Shanghai}") String zoneId) {

        return Clock.system(ZoneId.of(zoneId));
    }
}