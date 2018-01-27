package com.github.yukinomiu.hikari.common.exception;

/**
 * Yukinomiu
 * 2018/1/26
 */
public class HikariChecksumFailException extends HikariRuntimeException {
    public HikariChecksumFailException() {
    }

    public HikariChecksumFailException(String message) {
        super(message);
    }

    public HikariChecksumFailException(String message, Throwable cause) {
        super(message, cause);
    }
}
