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

    private SelectionKey selectionKey;
    private ServerClientContext clientContext;

    public ServerTargetContext() {
        super(ServerContextType.TARGET);
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

    public void setSelectionKey(SelectionKey selectionKey) {
        this.selectionKey = selectionKey;
    }

    public ServerClientContext getClientContext() {
        return clientContext;
    }

    public void setClientContext(ServerClientContext clientContext) {
        this.clientContext = clientContext;
    }
}
