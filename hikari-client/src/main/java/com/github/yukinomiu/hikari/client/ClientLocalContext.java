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

    private SocksStatus status;
    private ClientRemoteContext remoteContext;

    // socks protocol
    private byte socksVersion;

    // target info
    private byte hikariAddressType;
    private byte[] address;
    private byte[] port;

    public ClientLocalContext(final SelectionKey key,
                              final Integer bufferSize,
                              final SocksStatus status) {
        super(ClientContextType.LOCAL, key, bufferSize);
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
                SocketChannel socketChannel = (SocketChannel) key.channel();
                socketChannel.close();
            } catch (IOException e) {
                logger.warn("close local socket channel exception, msg: {}", e.getMessage());
            }
        }

        if (remoteContext != null) {
            remoteContext.close();
        }
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

    public byte getSocksVersion() {
        return socksVersion;
    }

    public void setSocksVersion(byte socksVersion) {
        this.socksVersion = socksVersion;
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
