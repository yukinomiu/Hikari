package com.github.yukinomiu.hikari.common;

import java.nio.channels.SelectionKey;

/**
 * Yukinomiu
 * 2018/1/22
 */
public interface HikariHandle {

    void handleAccept(final SelectionKey key);

    void handleConnect(final SelectionKey key);

    void handleRead(final SelectionKey key);

    void handleWrite(final SelectionKey key);
}
