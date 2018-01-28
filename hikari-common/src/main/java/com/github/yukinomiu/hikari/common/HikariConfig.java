package com.github.yukinomiu.hikari.common;

/**
 * Yukinomiu
 * 2018/1/27
 */
public class HikariConfig {

    private Integer bufferSize;
    private String encryptType;
    private String secret;

    public Integer getBufferSize() {
        return bufferSize;
    }

    public void setBufferSize(Integer bufferSize) {
        this.bufferSize = bufferSize;
    }

    public String getEncryptType() {
        return encryptType;
    }

    public void setEncryptType(String encryptType) {
        this.encryptType = encryptType;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }
}
