package com.hrsecurity.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：Controller 抛出的异常统一在这里转成 Result JSON，
 * 避免每个接口重复写 try-catch。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：按业务 code 返回 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 方法级鉴权失败（@PreAuthorize 校验不通过时抛出的异常）。
     * 必须放在 catch-all 之前单独处理，否则会被 Exception 兜底转成 500。
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    public Result<Void> handleAccessDenied(AuthorizationDeniedException e) {
        return Result.error(ResultCode.FORBIDDEN.getCode(), "无权限访问");
    }

    /** 参数校验失败（@Valid 触发）：取第一个字段的错误提示 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError == null ? "参数校验失败" : fieldError.getDefaultMessage();
        return Result.error(ResultCode.BAD_REQUEST.getCode(), message);
    }

    /** 兜底异常：不把堆栈暴露给前端 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error(ResultCode.ERROR.getCode(), ResultCode.ERROR.getMessage());
    }
}
