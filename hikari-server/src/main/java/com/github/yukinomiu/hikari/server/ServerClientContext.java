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

    private final SelectionKey selectionKey;
    private final PacketContext packetContext;

    private HikariStatus status;
    private ServerTargetContext targetContext;

    public ServerClientContext(final SelectionKey selectionKey, final PacketContext packetContext, final HikariStatus status) {
        super(ServerContextType.CLIENT);

        this.selectionKey = selectionKey;
        this.packetContext = packetContext;
        this.status = status;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        if (selectionKey != null) {
            selectionKey.cancel();

            try {
                SocketChannel socketChannel = (SocketChannel) selectionKey.channel();
                socketChannel.close();
            } catch (IOException e) {
                logger.warn("close client socket channel exception, msg: {}", e.getMessage());
            }
        }

        if (targetContext != null) {
            targetContext.close();
        }
    }

    public SelectionKey getSelectionKey() {
        return selectionKey;
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
