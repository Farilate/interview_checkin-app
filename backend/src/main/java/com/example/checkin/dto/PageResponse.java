package com.example.checkin.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * 通用分页响应。
 *
 * @param <T> 列表元素类型
 */
@Getter
@AllArgsConstructor
public class PageResponse<T> {

    private final List<T> items;

    private final long total;

    private final int page;

    private final int pageSize;
}