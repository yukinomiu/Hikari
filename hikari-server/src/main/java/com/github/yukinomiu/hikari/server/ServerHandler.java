package com.github.yukinomiu.hikari.server;

import com.github.yukinomiu.hikari.common.HikariAbstractHandle;
import com.github.yukinomiu.hikari.common.HikariConstant;
import com.github.yukinomiu.hikari.common.HikariStatus;
import com.github.yukinomiu.hikari.common.PacketContext;
import com.github.yukinomiu.hikari.common.exception.HikariRuntimeException;
import com.github.yukinomiu.hikari.common.protocol.HikariProtocol;
import com.github.yukinomiu.hikari.common.util.HexUtil;
import com.github.yukinomiu.hikari.common.util.Md5Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Yukinomiu
 * 2018/1/22
 */
public class ServerHandler extends HikariAbstractHandle {
    private static final Logger logger = LoggerFactory.getLogger(ServerHandler.class);
    private final ServerConfig config;

    private final Integer bufferSize;
    private final ByteBuffer dataBuffer;
    private final ByteBuffer cacheBuffer;
    private final ByteBuffer cryptoBuffer;
    private final ByteBuffer packetBuffer;

    private final Set<String> privateKeyHashSet;

    public ServerHandler(final ServerConfig config) {
        super(config);

        // config
        this.config = config;

        // buffer
        bufferSize = config.getBufferSize();
        dataBuffer = ByteBuffer.allocateDirect(bufferSize);
        cacheBuffer = ByteBuffer.allocateDirect(bufferSize << 1);
        cryptoBuffer = ByteBuffer.allocateDirect(bufferSize);
        packetBuffer = ByteBuffer.allocateDirect(bufferSize + HikariConstant.PACKET_WRAPPER_SIZE);

        // private keys
        List<String> privateKeyList = config.getPrivateKeyList();
        privateKeyHashSet = new HashSet<>(privateKeyList.size(), 1);

        Md5Util md5Util = Md5Util.getInstance();
        for (String key : privateKeyList) {
            String hexString = md5Util.md5String(key);
            privateKeyHashSet.add(hexString);
        }
    }

    @Override
    public void handleAccept(final SelectionKey key) {
        final Selector selector = key.selector();
        final ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();

        try {
            SocketChannel channel = serverChannel.accept();
            channel.configureBlocking(false);

            SelectionKey clientKey = channel.register(selector, SelectionKey.OP_READ);

            ServerClientContext clientContext = new ServerClientContext(clientKey, bufferSize, HikariStatus.HIKARI_AUTH);
            clientKey.attach(clientContext);
        } catch (Exception e) {
            String msg = e.getMessage();
            logger.warn("handle accept exception: {}", msg != null ? msg : e.getClass().getName());
        }
    }

    @Override
    public void handleConnect(final SelectionKey key) {
        final ServerTargetContext targetContext = (ServerTargetContext) key.attachment();
        final SocketChannel targetChannel = (SocketChannel) key.channel();

        final ServerClientContext clientContext = targetContext.getClientContext();
        final SocketChannel clientChannel = (SocketChannel) clientContext.key().channel();

        try {
            try {
                targetChannel.finishConnect();
            } catch (IOException e) {
                logger.warn("connect to target fail, msg: {}", e.getMessage());
                writeHikariFail(HikariProtocol.AUTH_RESPONSE_CONNECT_TARGET_FAIL, clientChannel, targetContext);
                return;
            }

            key.interestOps(SelectionKey.OP_READ);

            // response
            final Socket targetSocket = targetChannel.socket();
            final InetAddress localAddress = targetSocket.getLocalAddress();
            final short port = (short) targetSocket.getLocalPort();

            final byte[] bindAddress = localAddress.getAddress();
            final byte bindHikariAddressType;

            if (localAddress instanceof Inet4Address) {
                bindHikariAddressType = HikariProtocol.ADDRESS_TYPE_IPV4;
            }
            else if (localAddress instanceof Inet6Address) {
                bindHikariAddressType = HikariProtocol.ADDRESS_TYPE_IPV6;
            }
            else {
                throw new HikariRuntimeException(String.format("address type '%s' not supported", localAddress.getClass().getName()));
            }

            dataBuffer.clear();
            dataBuffer.put(HikariProtocol.VERSION_HIKARI1);
            dataBuffer.put(HikariProtocol.AUTH_RESPONSE_OK);
            dataBuffer.put(bindHikariAddressType);
            dataBuffer.put(bindAddress);
            dataBuffer.putShort(port);
            dataBuffer.flip();

            // encrypt
            encrypt(dataBuffer, cryptoBuffer, packetBuffer);

            // write
            clientChannel.write(packetBuffer);
            if (packetBuffer.hasRemaining()) {
                logger.warn("send hikari auth response fail");
                targetContext.close();
                return;
            }

            // set status
            clientContext.setStatus(HikariStatus.HIKARI_PROXY);
        } catch (Exception e) {
            String msg = e.getMessage();
            logger.warn("handle connect exception: {}", msg != null ? msg : e.getClass().getName());
            targetContext.close();
        }
    }

    @Override
    public void handleRead(final SelectionKey key) {
        final ServerContext context = (ServerContext) key.attachment();
        final ServerContextType type = context.getType();

        try {
            if (type == ServerContextType.CLIENT) {
                final ServerClientContext clientContext = (ServerClientContext) context;
                final HikariStatus status = clientContext.getStatus();

                switch (status) {
                    case HIKARI_AUTH:
                        processHikariAuthRead(key, clientContext);
                        break;

                    case HIKARI_PROXY:
                        processHikariProxyRead(key, clientContext);
                        break;

                    default:
                        throw new HikariRuntimeException(String.format("server hikari status '%s' not supported", status.name()));
                }
            }
            else if (type == ServerContextType.TARGET) {
                final ServerTargetContext serverTargetContext = (ServerTargetContext) context;

                processTargetRead(key, serverTargetContext);
            }
            else {
                throw new HikariRuntimeException(String.format("server context type '%s' not supported", type.name()));
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            logger.warn("handle read exception: {}", msg != null ? msg : e.getClass().getName());
            context.close();
        }
    }

    @Override
    public void handleWrite(final SelectionKey key) {
        final ServerContext context = (ServerContext) key.attachment();
        final ServerContextType type = context.getType();

        try {
            if (type == ServerContextType.CLIENT) {
                final ServerClientContext clientContext = (ServerClientContext) context;
                final SocketChannel clientChannel = (SocketChannel) key.channel();
                final ByteBuffer writeBuffer = clientContext.writeBuffer();

                clientChannel.write(writeBuffer);
                if (!writeBuffer.hasRemaining()) {
                    final SelectionKey targetKey = clientContext.getTargetContext().key();

                    key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                    targetKey.interestOps(targetKey.interestOps() | SelectionKey.OP_READ);
                }
            }
            else if (type == ServerContextType.TARGET) {
                final ServerTargetContext targetContext = (ServerTargetContext) context;
                final SocketChannel targetChannel = (SocketChannel) key.channel();
                final ByteBuffer writeBuffer = targetContext.writeBuffer();

                targetChannel.write(writeBuffer);
                if (!writeBuffer.hasRemaining()) {
                    final SelectionKey clientKey = targetContext.getClientContext().key();

                    key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                    clientKey.interestOps(clientKey.interestOps() | SelectionKey.OP_READ);
                }
            }
            else {
                throw new HikariRuntimeException(String.format("server context type '%s' not supported", type.name()));
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            logger.warn("handle write exception: {}", msg != null ? msg : e.getClass().getName());
            context.close();
        }
    }

    private void processHikariAuthRead(final SelectionKey key,
                                       final ServerClientContext clientContext) throws IOException {
        final SocketChannel clientChannel = (SocketChannel) key.channel();
        if (!read(clientChannel, packetBuffer, clientContext)) {
            return;
        }

        final PacketContext packetContext = clientContext.getPacketContext();

        // decrypt
        cacheBuffer.clear();
        while (decrypt(packetBuffer, cryptoBuffer, dataBuffer, packetContext)) {
            cacheBuffer.put(dataBuffer);
        }
        cacheBuffer.flip();

        // ver
        final byte ver = cacheBuffer.get();
        if (ver != HikariProtocol.VERSION_HIKARI1) {
            writeHikariFail(HikariProtocol.AUTH_RESPONSE_VERSION_NOT_SUPPORT, clientChannel, clientContext);
            return;
        }

        // auth
        final byte[] auth = new byte[16];
        cacheBuffer.get(auth);
        String keyHashHex = HexUtil.hexString(auth);
        if (!privateKeyHashSet.contains(keyHashHex)) {
            writeHikariFail(HikariProtocol.AUTH_RESPONSE_AUTH_FAIL, clientChannel, clientContext);
            return;
        }

        // address
        final byte hikariAddressType = cacheBuffer.get();
        final byte[] address;
        if (hikariAddressType == HikariProtocol.ADDRESS_TYPE_DOMAIN) {
            // resolve
            int length = cacheBuffer.get();
            byte[] domainByteArray = new byte[length];
            cacheBuffer.get(domainByteArray, 0, length);

            InetAddress inetAddress;
            final String domain = new String(domainByteArray, StandardCharsets.UTF_8);
            try {
                inetAddress = InetAddress.getByName(domain);
            } catch (UnknownHostException e) {
                logger.warn("DNS resolve fail: {}", domain);
                writeHikariFail(HikariProtocol.AUTH_RESPONSE_DNS_RESOLVE_FAIL, clientChannel, clientContext);
                return;
            }

            address = inetAddress.getAddress();
        }
        else if (hikariAddressType == HikariProtocol.ADDRESS_TYPE_IPV4) {
            address = new byte[4];
            cacheBuffer.get(address, 0, 4);
        }
        else if (hikariAddressType == HikariProtocol.ADDRESS_TYPE_IPV6) {
            address = new byte[16];
            cacheBuffer.get(address, 0, 16);
        }
        else {
            throw new HikariRuntimeException(String.format("hikari address type '%s' not supported", hikariAddressType));
        }

        // port
        final short port = cacheBuffer.getShort();

        if (cacheBuffer.hasRemaining()) {
            logger.warn("bad hikari auth request");
            clientContext.close();
            return;
        }

        // connect to target
        InetAddress inetAddress = InetAddress.getByAddress(address);

        final SelectionKey clientKey = clientContext.key();
        final Selector selector = clientKey.selector();
        final SocketAddress targetAddress = new InetSocketAddress(inetAddress, port);

        SocketChannel targetChannel = SocketChannel.open();
        targetChannel.configureBlocking(false);

        final SelectionKey targetKey = targetChannel.register(selector, SelectionKey.OP_CONNECT);
        ServerTargetContext targetContext = new ServerTargetContext(targetKey, bufferSize, clientContext);
        targetKey.attach(targetContext);

        clientContext.setTargetContext(targetContext);

        boolean connectedNow = targetChannel.connect(targetAddress);
        if (connectedNow) {
            handleConnect(targetKey);
        }
    }

    private void processHikariProxyRead(final SelectionKey key,
                                        final ServerClientContext clientContext) throws IOException {
        final SocketChannel clientChannel = (SocketChannel) key.channel();

        if (!read(clientChannel, packetBuffer, clientContext)) {
            return;
        }

        // decrypt
        final PacketContext packetContext = clientContext.getPacketContext();
        final ServerTargetContext targetContext = clientContext.getTargetContext();

        cacheBuffer.clear();
        while (decrypt(packetBuffer, cryptoBuffer, dataBuffer, packetContext)) {
            cacheBuffer.put(dataBuffer);
        }
        cacheBuffer.flip();

        // write
        write(clientContext, targetContext, cacheBuffer);
    }

    private void processTargetRead(final SelectionKey key,
                                   final ServerTargetContext targetContext) throws IOException {
        final SocketChannel targetChannel = (SocketChannel) key.channel();
        if (!read(targetChannel, dataBuffer, targetContext)) {
            return;
        }

        // encrypt
        encrypt(dataBuffer, cryptoBuffer, packetBuffer);

        // write
        final ServerClientContext clientContext = targetContext.getClientContext();
        write(targetContext, clientContext, packetBuffer);
    }

    private void writeHikariFail(final byte rsp,
                                 final SocketChannel channel,
                                 final ServerContext context) throws IOException {
        dataBuffer.clear();
        dataBuffer.put(HikariProtocol.VERSION_HIKARI1);
        dataBuffer.put(rsp);
        dataBuffer.flip();

        // encrypt
        encrypt(dataBuffer, cryptoBuffer, packetBuffer);

        // write
        channel.write(packetBuffer);
        if (packetBuffer.hasRemaining()) {
            logger.warn("send hikari auth response fail");
        }

        context.close();
    }
}
