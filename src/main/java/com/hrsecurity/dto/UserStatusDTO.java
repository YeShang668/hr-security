package com.hrsecurity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 启用/禁用账号请求参数：只允许 0/1，避免前端传别的值污染 status 列。
 */
@Data
public class UserStatusDTO {

    /** 1启用 0禁用 */
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态只能是 0(禁用) 或 1(启用)")
    @Max(value = 1, message = "状态只能是 0(禁用) 或 1(启用)")
    private Integer status;
}
