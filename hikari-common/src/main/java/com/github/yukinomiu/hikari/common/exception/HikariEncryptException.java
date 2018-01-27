package com.github.yukinomiu.hikari.common.exception;

/**
 * Yukinomiu
 * 2018/1/27
 */
public class HikariEncryptException extends HikariRuntimeException {
    public HikariEncryptException() {
    }

    public HikariEncryptException(String message) {
        super(message);
    }

    public HikariEncryptException(String message, Throwable cause) {
        super(message, cause);
    }
}
