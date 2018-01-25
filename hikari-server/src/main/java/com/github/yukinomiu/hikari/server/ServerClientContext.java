package com.github.yukinomiu.hikari.server;

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
public class ServerClientContext extends ServerContext {
    private static final Logger logger = LoggerFactory.getLogger(ServerClientContext.class);

    private boolean closed = false;

    private SelectionKey selectionKey;
    private HikariStatus status;
    private ServerTargetContext targetContext;

    public ServerClientContext() {
        super(ServerContextType.CLIENT);
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

    public void setSelectionKey(SelectionKey selectionKey) {
        this.selectionKey = selectionKey;
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
