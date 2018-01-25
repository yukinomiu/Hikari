package com.github.yukinomiu.hikari.common;

/**
 * Yukinomiu
 * 2018/1/11
 */
public interface LifeCycle {
    void start() throws Exception;

    void shutdown() throws Exception;
}
