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

    private final ServerClientContext clientContext;

    public ServerTargetContext(final SelectionKey key,
                               final Integer bufferSize,
                               final ServerClientContext clientContext) {
        super(ServerContextType.TARGET, key, bufferSize);
        this.clientContext = clientContext;
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
                logger.warn("close target socket channel exception, msg: {}", e.getMessage());
            }
        }

        if (clientContext != null) {
            clientContext.close();
        }
    }

    public ServerClientContext getClientContext() {
        return clientContext;
    }
}
