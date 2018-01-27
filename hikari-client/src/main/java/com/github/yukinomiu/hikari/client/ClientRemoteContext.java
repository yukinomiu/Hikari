package com.github.yukinomiu.hikari.client;

import com.github.yukinomiu.hikari.common.EncryptTransfer;
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
public class ClientRemoteContext extends ClientContext implements EncryptTransfer {
    private static final Logger logger = LoggerFactory.getLogger(ClientRemoteContext.class);

    private boolean closed = false;

    private final SelectionKey selectionKey;
    private final PacketContext packetContext;
    private final ClientLocalContext localContext;

    private HikariStatus status;

    public ClientRemoteContext(final SelectionKey selectionKey, final PacketContext packetContext, final ClientLocalContext localContext, final HikariStatus status) {
        super(ClientContextType.REMOTE);

        this.selectionKey = selectionKey;
        this.packetContext = packetContext;
        this.localContext = localContext;
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
                logger.warn("close remote socket channel exception, msg: {}", e.getMessage());
            }
        }

        if (localContext != null) {
            localContext.close();
        }
    }

    public SelectionKey getSelectionKey() {
        return selectionKey;
    }

    public ClientLocalContext getLocalContext() {
        return localContext;
    }

    @Override
    public PacketContext getPacketContext() {
        return packetContext;
    }

    public HikariStatus getStatus() {
        return status;
    }

    public void setStatus(HikariStatus status) {
        this.status = status;
    }
}
