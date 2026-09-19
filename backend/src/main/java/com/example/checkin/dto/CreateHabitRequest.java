package com.example.checkin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 创建打卡项请求。
 *
 * <p>用户身份由登录 Session 确定，
 * 客户端不能自行提交 userId。
 */
@Getter
@Setter
public class CreateHabitRequest {

    /** 打卡项名称。 */
    @NotBlank
    @Size(max = 50)
    private String name;

    /** 可选描述。 */
    @Size(max = 200)
    private String description;
}