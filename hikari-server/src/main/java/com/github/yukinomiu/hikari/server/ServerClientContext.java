package com.github.yukinomiu.hikari.server;

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
public class ServerClientContext extends ServerContext {
    private static final Logger logger = LoggerFactory.getLogger(ServerClientContext.class);
    private boolean closed = false;
    private final PacketContext packetContext;

    private HikariStatus status;
    private ServerTargetContext targetContext;

    public ServerClientContext(final SelectionKey key,
                               final Integer bufferSize,
                               final HikariStatus status) {
        super(ServerContextType.CLIENT, key, bufferSize);
        packetContext = new PacketContext(bufferSize);
        this.status = status;
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
                SocketChannel channel = (SocketChannel) key.channel();
                channel.close();
            } catch (IOException e) {
                logger.warn("close client socket channel exception, msg: {}", e.getMessage());
            }
        }

        if (targetContext != null) {
            targetContext.close();
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

    public ServerTargetContext getTargetContext() {
        return targetContext;
    }

    public void setTargetContext(ServerTargetContext targetContext) {
        this.targetContext = targetContext;
    }
}
