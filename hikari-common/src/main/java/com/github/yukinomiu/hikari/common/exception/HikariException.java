package com.github.yukinomiu.hikari.common.exception;

/**
 * Yukinomiu
 * 2018/1/22
 */
public class HikariException extends Exception {
    public HikariException() {
    }

    public HikariException(String message) {
        super(message);
    }

    public HikariException(String message, Throwable cause) {
        super(message, cause);
    }
}
