package com.example.checkin.dto;

import jakarta.validation.constraints.NotBlank;
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

    /** 打卡项名称；非空白校验在入口执行，trim 后的码点长度由 Service 校验。 */
    @NotBlank
    private String name;

    /** 可选描述；Service 按 Unicode 码点限制 200，并将空白描述归一为 null。 */
    private String description;
}
