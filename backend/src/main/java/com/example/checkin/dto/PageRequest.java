package com.example.checkin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/**
 * 列表接口的公共分页参数，后续 Controller 通过 @Valid @ModelAttribute 接收。
 * 仅校验分页范围，不接收 userId；用户身份由服务端鉴权提供。
 */
@Getter
@Setter
public class PageRequest {
    /** 从 1 开始的页码；默认第一页。 */
    @Min(1)
    private int page = 1;

    /** 每页条数，限制上限以避免一次请求读取过量数据。 */
    @Min(1)
    @Max(100)
    private int pageSize = 20;

    /** 转换为 Mapper 所需的行偏移，先提升为 long 避免 int 乘法溢出。 */
    public long offset() {
        return ((long) page - 1) * pageSize;
    }
}
