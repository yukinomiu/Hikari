package com.github.yukinomiu.hikari.server;

import com.github.yukinomiu.hikari.common.HikariConstant;
import com.github.yukinomiu.hikari.common.HikariHandle;
import com.github.yukinomiu.hikari.common.HikariStatus;
import com.github.yukinomiu.hikari.common.PacketContext;
import com.github.yukinomiu.hikari.common.exception.HikariRuntimeException;
import com.github.yukinomiu.hikari.common.protocol.HikariProtocol;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
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
public class ServerHandler implements HikariHandle {
    private static final Logger logger = LoggerFactory.getLogger(ServerHandler.class);

    private final ServerConfig serverConfig;
    private final ByteBuffer readBuffer;
    private final ByteBuffer writeBuffer;

    private final Set<String> privateKeyHashSet;

    public ServerHandler(final ServerConfig serverConfig) {
        this.serverConfig = serverConfig;

        // init buffer
        Integer bufferSize = HikariConstant.BUFFER_SIZE;
        readBuffer = ByteBuffer.allocateDirect(bufferSize);
        writeBuffer = ByteBuffer.allocateDirect(bufferSize + 2);

        // init private keys
        List<String> privateKeyList = serverConfig.getPrivateKeyList();
        privateKeyHashSet = new HashSet<>(privateKeyList.size(), 1);
        for (String key : privateKeyList) {
            String hexString = DigestUtils.md5Hex(key);
            privateKeyHashSet.add(hexString);
        }
    }

    @Override
    public void handleAccept(final SelectionKey selectionKey) {
        final Selector selector = selectionKey.selector();
        final ServerSocketChannel serverSocketChannel = (ServerSocketChannel) selectionKey.channel();

        try {
            SocketChannel socketChannel = serverSocketChannel.accept();
            socketChannel.configureBlocking(false);

            final SelectionKey clientKey = socketChannel.register(selector, SelectionKey.OP_READ);

            PacketContext packetContext = new PacketContext(ByteBuffer.allocateDirect(writeBuffer.capacity()));
            ServerClientContext clientContext = new ServerClientContext(clientKey, packetContext, HikariStatus.HIKARI_AUTH);
            clientKey.attach(clientContext);
        } catch (Exception e) {
            String msg = e.getMessage();
            logger.warn("handle accept exception: {}", msg != null ? msg : e.getClass().getName());
        }
    }

    @Override
    public void handleConnect(final SelectionKey selectionKey) {
        final ServerTargetContext serverTargetContext = (ServerTargetContext) selectionKey.attachment();
        final ServerClientContext serverClientContext = serverTargetContext.getClientContext();

        final SocketChannel targetChannel = (SocketChannel) selectionKey.channel();
        final SocketChannel clientChannel = (SocketChannel) serverClientContext.getSelectionKey().channel();

        try {
            try {
                targetChannel.finishConnect();
            } catch (IOException e) {
                logger.warn("connect to target fail, msg: {}", e.getMessage());
                writeAuthFailResponse(HikariProtocol.AUTH_RESPONSE_CONNECT_TARGET_FAIL, clientChannel, serverTargetContext);
                return;
            }

            selectionKey.interestOps(SelectionKey.OP_READ);

            // response
            final Socket targetSocket = targetChannel.socket();
            final InetAddress localAddress = targetSocket.getLocalAddress();
            final short port = (short) targetSocket.getLocalPort();

            final byte bindHikariAddressType;
            final byte[] bindAddress = localAddress.getAddress();

            if (localAddress instanceof Inet4Address) {
                bindHikariAddressType = HikariProtocol.ADDRESS_TYPE_IPV4;
            }
            else if (localAddress instanceof Inet6Address) {
                bindHikariAddressType = HikariProtocol.ADDRESS_TYPE_IPV6;
            }
            else {
                throw new HikariRuntimeException(String.format("address type '%s' not supported", localAddress.toString()));
            }

            writeBuffer.clear();
            writeBuffer.put(HikariProtocol.VERSION_HIKARI1);
            writeBuffer.put(HikariProtocol.AUTH_RESPONSE_OK);
            writeBuffer.put(bindHikariAddressType);
            writeBuffer.put(bindAddress);
            writeBuffer.putShort(port);
            writeBuffer.flip();
            clientChannel.write(writeBuffer);

            // set status
            serverClientContext.setStatus(HikariStatus.HIKARI_PROXY);
        } catch (Exception e) {
            String msg = e.getMessage();
            logger.warn("handle connect exception: {}", msg != null ? msg : e.getClass().getName());

            serverTargetContext.close();
        }
    }

    @Override
    public void handleRead(final SelectionKey selectionKey) {
        final SocketChannel socketChannel = (SocketChannel) selectionKey.channel();
        final ServerContext serverContext = (ServerContext) selectionKey.attachment();
        final ServerContextType type = serverContext.getType();

        try {
            if (type == ServerContextType.CLIENT) {
                final ServerClientContext serverClientContext = (ServerClientContext) serverContext;
                final HikariStatus status = serverClientContext.getStatus();

                switch (status) {
                    case HIKARI_AUTH:
                        processHikariAuthRead(socketChannel, serverClientContext);
                        break;

                    case HIKARI_PROXY:
                        processHikariProxyRead(socketChannel, serverClientContext);
                        break;

                    default:
                        throw new HikariRuntimeException(String.format("server status '%s' not supported", status.name()));
                }
            }
            else if (type == ServerContextType.TARGET) {
                final ServerTargetContext serverTargetContext = (ServerTargetContext) serverContext;

                processTargetRead(socketChannel, serverTargetContext);
            }
            else {
                throw new HikariRuntimeException(String.format("server context type '%s' not supported", type.name()));
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            logger.warn("handle read exception: {}", msg != null ? msg : e.getClass().getName());

            serverContext.close();
        }
    }

    private void processHikariAuthRead(final SocketChannel socketChannel, final ServerClientContext serverClientContext) throws IOException {
        if (!readToBuffer(socketChannel, serverClientContext, readBuffer)) {
            return;
        }

        // version
        final byte version = readBuffer.get();
        if (version != HikariProtocol.VERSION_HIKARI1) {
            writeAuthFailResponse(HikariProtocol.AUTH_RESPONSE_VERSION_NOT_SUPPORT, socketChannel, serverClientContext);
            return;
        }

        // auth
        final byte[] auth = new byte[16];
        readBuffer.get(auth);
        String keyHashHex = Hex.encodeHexString(auth);
        if (!privateKeyHashSet.contains(keyHashHex)) {
            writeAuthFailResponse(HikariProtocol.AUTH_RESPONSE_AUTH_FAIL, socketChannel, serverClientContext);
            return;
        }

        // encrypt type
        final byte encryptType = readBuffer.get();
        if (encryptType != HikariProtocol.ENCRYPT_PLAIN) {
            writeAuthFailResponse(HikariProtocol.AUTH_RESPONSE_ENCRYPT_TYPE_NOT_SUPPORT, socketChannel, serverClientContext);
            return;
        }

        // address
        final byte hikariAddressType = readBuffer.get();
        final byte[] address;
        if (hikariAddressType == HikariProtocol.ADDRESS_TYPE_DOMAIN) {
            // resolve
            int length = readBuffer.get();
            byte[] tmpArray = new byte[length];
            readBuffer.get(tmpArray, 0, length);

            String domainName = new String(tmpArray, StandardCharsets.US_ASCII);
            InetAddress inetAddress;
            try {
                inetAddress = InetAddress.getByName(domainName);
            } catch (UnknownHostException e) {
                logger.error("DNS resolve fail: {}", domainName);
                writeAuthFailResponse(HikariProtocol.AUTH_RESPONSE_DNS_RESOLVE_FAIL, socketChannel, serverClientContext);
                return;
            }

            address = inetAddress.getAddress();
        }
        else if (hikariAddressType == HikariProtocol.ADDRESS_TYPE_IPV4) {
            address = new byte[4];
            readBuffer.get(address, 0, 4);
        }
        else if (hikariAddressType == HikariProtocol.ADDRESS_TYPE_IPV6) {
            address = new byte[16];
            readBuffer.get(address, 0, 16);
        }
        else {
            throw new HikariRuntimeException(String.format("hikari address type '%s' not supported", hikariAddressType));
        }

        // port
        final short port = readBuffer.getShort();

        // connect to target
        InetAddress inetAddress = InetAddress.getByAddress(address);

        final SelectionKey clientKey = serverClientContext.getSelectionKey();
        final Selector selector = clientKey.selector();
        final SocketAddress targetAddress = new InetSocketAddress(inetAddress, port);

        SocketChannel targetChannel = SocketChannel.open();
        targetChannel.configureBlocking(false);

        final SelectionKey targetKey = targetChannel.register(selector, SelectionKey.OP_CONNECT);
        ServerTargetContext targetContext = new ServerTargetContext(targetKey, serverClientContext);
        targetKey.attach(targetContext);

        serverClientContext.setTargetContext(targetContext);

        boolean connectedNow = targetChannel.connect(targetAddress);
        if (connectedNow) {
            handleConnect(targetKey);
        }
    }

    private void processHikariProxyRead(final SocketChannel socketChannel, final ServerClientContext serverClientContext) throws IOException {
        if (!readToBuffer(socketChannel, serverClientContext, readBuffer)) {
            return;
        }

        final SocketChannel targetChannel = (SocketChannel) serverClientContext.getTargetContext().getSelectionKey().channel();
        final PacketContext packetContext = serverClientContext.getPacketContext();

        while (unwrapPacket(readBuffer, writeBuffer, packetContext)) {
            targetChannel.write(writeBuffer);
        }
    }

    private void processTargetRead(SocketChannel socketChannel, ServerTargetContext serverTargetContext) throws IOException {
        if (!readToBuffer(socketChannel, serverTargetContext, readBuffer)) {
            return;
        }

        wrapPacket(readBuffer, writeBuffer);

        final SocketChannel clientChannel = (SocketChannel) serverTargetContext.getClientContext().getSelectionKey().channel();
        clientChannel.write(writeBuffer);
    }

    private void writeAuthFailResponse(final byte response, final SocketChannel socketChannel, final ServerContext serverContext) throws IOException {
        writeBuffer.clear();
        writeBuffer.put(HikariProtocol.VERSION_HIKARI1);
        writeBuffer.put(response);
        writeBuffer.flip();

        socketChannel.write(writeBuffer);
        serverContext.close();
    }
}
