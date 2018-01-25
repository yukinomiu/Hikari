package com.github.yukinomiu.hikari.common;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * Yukinomiu
 * 2018/1/22
 */
public interface HikariHandle {
    void handleAccept(final SelectionKey selectionKey);

    void handleConnect(final SelectionKey selectionKey);

    void handleRead(final SelectionKey selectionKey);

    default boolean read(final SocketChannel socketChannel, final HikariContext context, final ByteBuffer buffer) throws IOException {
        int read = socketChannel.read(buffer);

        if (read == -1) {
            context.close();
            return false;
        }
        else if (read == 0) {
            return false;
        }
        else {
            buffer.flip();
            return true;
        }
    }
}
