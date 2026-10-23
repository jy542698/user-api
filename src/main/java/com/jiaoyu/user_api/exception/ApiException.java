package com.jiaoyu.user_api.exception;

// 业务异常：带上 HTTP 状态码，方便全局异常处理器统一返回
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
