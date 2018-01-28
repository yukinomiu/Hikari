package com.github.yukinomiu.hikari.client;

import com.github.yukinomiu.hikari.common.HikariStatus;
import com.github.yukinomiu.hikari.common.PacketContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * Yukinomiu
 * 2018/1/24
 */
public class ClientRemoteContext extends ClientContext {
    private static final Logger logger = LoggerFactory.getLogger(ClientRemoteContext.class);
    private boolean closed = false;
    private final PacketContext packetContext;

    private HikariStatus status;
    private final ClientLocalContext localContext;

    public ClientRemoteContext(final SelectionKey key,
                               final Integer bufferSize,
                               final HikariStatus status,
                               final ClientLocalContext localContext) {
        super(ClientContextType.REMOTE, key, bufferSize);
        packetContext = new PacketContext(bufferSize);
        this.status = status;
        this.localContext = localContext;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        final SelectionKey key = key();
        if (key != null) {
            key.cancel();

            try {
                SocketChannel socketChannel = (SocketChannel) key.channel();
                socketChannel.close();
            } catch (IOException e) {
                logger.warn("close remote socket channel exception, msg: {}", e.getMessage());
            }
        }

        if (localContext != null) {
            localContext.close();
        }
    }

    public PacketContext getPacketContext() {
        return packetContext;
    }

    public HikariStatus getStatus() {
        return status;
    }

    public void setStatus(HikariStatus status) {
        this.status = status;
    }

    public ClientLocalContext getLocalContext() {
        return localContext;
    }
}
