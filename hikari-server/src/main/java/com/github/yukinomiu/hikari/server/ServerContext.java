package com.github.yukinomiu.hikari.server;

import com.github.yukinomiu.hikari.common.HikariContext;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;

/**
 * Yukinomiu
 * 2018/1/22
 */
public abstract class ServerContext implements HikariContext {

    private final ServerContextType type;
    private final SelectionKey key;
    private final ByteBuffer writeBuffer;

    protected ServerContext(final ServerContextType type, final SelectionKey key, final int bufferSize) {
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

    public ServerContextType getType() {
        return type;
    }
}
