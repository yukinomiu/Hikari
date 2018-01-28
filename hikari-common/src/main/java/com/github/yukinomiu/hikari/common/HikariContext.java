package com.github.yukinomiu.hikari.common;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;

/**
 * Yukinomiu
 * 2018/1/22
 */
public interface HikariContext {

    SelectionKey key();

    ByteBuffer writeBuffer();

    void close();
}
