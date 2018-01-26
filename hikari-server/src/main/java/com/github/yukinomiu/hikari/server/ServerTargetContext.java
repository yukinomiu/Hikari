package com.github.yukinomiu.hikari.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * Yukinomiu
 * 2018/1/24
 */
public class ServerTargetContext extends ServerContext {
    private static final Logger logger = LoggerFactory.getLogger(ServerTargetContext.class);

    private boolean closed = false;

    private final SelectionKey selectionKey;
    private final ServerClientContext clientContext;

    public ServerTargetContext(final SelectionKey selectionKey, final ServerClientContext clientContext) {
        super(ServerContextType.TARGET);

        this.selectionKey = selectionKey;
        this.clientContext = clientContext;
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
                logger.warn("close target socket channel exception, msg: {}", e.getMessage());
            }
        }

        if (clientContext != null) {
            clientContext.close();
        }
    }

    public SelectionKey getSelectionKey() {
        return selectionKey;
    }

    public ServerClientContext getClientContext() {
        return clientContext;
    }
}
