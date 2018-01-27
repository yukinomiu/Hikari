package com.github.yukinomiu.hikari.common;

import java.nio.channels.SelectionKey;

/**
 * Yukinomiu
 * 2018/1/22
 */
public interface HikariHandle {
    void handleAccept(final SelectionKey selectionKey);

    void handleConnect(final SelectionKey selectionKey);

    void handleRead(final SelectionKey selectionKey);
}
