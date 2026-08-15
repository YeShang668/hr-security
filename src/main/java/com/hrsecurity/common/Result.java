package com.hrsecurity.common;

import lombok.Data;

/**
 * 统一接口返回结构：所有接口都返回 {code, message, data}
 * code == 200 表示成功；否则 message 携带错误原因
 */
@Data
public class Result<T> {

    private int code;
    private String message;
    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.code = ResultCode.SUCCESS.getCode();
        r.message = "操作成功";
        r.data = data;
        return r;
    }

    public static <T> Result<T> success(String message, T data) {
        Result<T> r = success(data);
        r.message = message;
        return r;
    }

    public static <T> Result<T> error(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }
}
