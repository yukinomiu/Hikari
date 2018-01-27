package com.github.yukinomiu.hikari.common.exception;

/**
 * Yukinomiu
 * 2018/1/27
 */
public class HikariDecryptException extends HikariRuntimeException {
    public HikariDecryptException() {
    }

    public HikariDecryptException(String message) {
        super(message);
    }

    public HikariDecryptException(String message, Throwable cause) {
        super(message, cause);
    }
}
