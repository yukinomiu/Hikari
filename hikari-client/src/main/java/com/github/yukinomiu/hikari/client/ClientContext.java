package com.github.yukinomiu.hikari.client;

import com.github.yukinomiu.hikari.common.HikariContext;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;

/**
 * Yukinomiu
 * 2018/1/24
 */
public abstract class ClientContext implements HikariContext {

    private final ClientContextType type;
    private final SelectionKey key;
    private final ByteBuffer writeBuffer;

    protected ClientContext(final ClientContextType type, final SelectionKey key, final int bufferSize) {
        this.type = type;
        this.key = key;
        this.writeBuffer = ByteBuffer.allocateDirect(bufferSize << 1);
    }

    @Override
    public final SelectionKey key() {
        return key;
    }

    @Override
    public final ByteBuffer writeBuffer() {
        return writeBuffer;
    }

    public ClientContextType getType() {
        return type;
    }
}
