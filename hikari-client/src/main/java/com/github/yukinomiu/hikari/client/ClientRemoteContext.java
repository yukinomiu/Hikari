package com.github.yukinomiu.hikari.client;

import com.github.yukinomiu.hikari.common.HikariStatus;
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

    private SelectionKey selectionKey;
    private HikariStatus status;
    private ClientLocalContext localContext;

    public ClientRemoteContext() {
        super(ClientContextType.REMOTE);
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

    public void setSelectionKey(SelectionKey selectionKey) {
        this.selectionKey = selectionKey;
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

    public void setLocalContext(ClientLocalContext localContext) {
        this.localContext = localContext;
    }
}
