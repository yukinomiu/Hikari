package com.github.yukinomiu.hikari.client;

import com.github.yukinomiu.hikari.common.SocksStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * Yukinomiu
 * 2018/1/22
 */
public class ClientLocalContext extends ClientContext {
    private static final Logger logger = LoggerFactory.getLogger(ClientLocalContext.class);

    private boolean closed = false;

    private SelectionKey selectionKey;
    private SocksStatus status;
    private ClientRemoteContext remoteContext;

    private byte hikariAddressType;
    private byte[] address;
    private byte[] port;

    public ClientLocalContext() {
        super(ClientContextType.LOCAL);
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
                logger.warn("close local socket channel exception, msg: {}", e.getMessage());
            }
        }

        if (remoteContext != null) {
            remoteContext.close();
        }
    }

    public SelectionKey getSelectionKey() {
        return selectionKey;
    }

    public void setSelectionKey(SelectionKey selectionKey) {
        this.selectionKey = selectionKey;
    }

    public SocksStatus getStatus() {
        return status;
    }

    public void setStatus(SocksStatus status) {
        this.status = status;
    }

    public ClientRemoteContext getRemoteContext() {
        return remoteContext;
    }

    public void setRemoteContext(ClientRemoteContext remoteContext) {
        this.remoteContext = remoteContext;
    }

    public byte getHikariAddressType() {
        return hikariAddressType;
    }

    public void setHikariAddressType(byte hikariAddressType) {
        this.hikariAddressType = hikariAddressType;
    }

    public byte[] getAddress() {
        return address;
    }

    public void setAddress(byte[] address) {
        this.address = address;
    }

    public byte[] getPort() {
        return port;
    }

    public void setPort(byte[] port) {
        this.port = port;
    }
}
