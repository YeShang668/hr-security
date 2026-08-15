package com.hrsecurity.common;

import lombok.Getter;

/**
 * 业务异常：Service 层校验失败时抛出，由 GlobalExceptionHandler 统一转成 JSON 返回。
 * 用法：throw new BusinessException(ResultCode.CONFLICT.getCode(), "用户名已存在");
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    /** 默认按 400 参数错误处理 */
    public BusinessException(String message) {
        this(ResultCode.BAD_REQUEST.getCode(), message);
    }
}
