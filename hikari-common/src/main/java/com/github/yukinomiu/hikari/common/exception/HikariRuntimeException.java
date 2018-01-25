package com.github.yukinomiu.hikari.common.exception;

/**
 * Yukinomiu
 * 2018/1/22
 */
public class HikariRuntimeException extends RuntimeException {
    public HikariRuntimeException() {
    }

    public HikariRuntimeException(String message) {
        super(message);
    }

    public HikariRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
