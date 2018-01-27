package com.github.yukinomiu.hikari.server;

import com.github.yukinomiu.hikari.common.HikariAbstractHandle;
import com.github.yukinomiu.hikari.common.HikariContext;
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

    private final ServerConfig serverConfig;

    private final Set<String> privateKeyHashSet;

    public ServerHandler(final ServerConfig serverConfig) {
        super(serverConfig);

        // config
        this.serverConfig = serverConfig;

        // private keys
        List<String> privateKeyList = serverConfig.getPrivateKeyList();
        privateKeyHashSet = new HashSet<>(privateKeyList.size(), 1);

        Md5Util md5Util = Md5Util.getInstance();
        for (String key : privateKeyList) {
            String hexString = md5Util.md5String(key);
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

            SelectionKey clientKey = socketChannel.register(selector, SelectionKey.OP_READ);

            PacketContext packetContext = getNewPacketContext();
            ServerClientContext serverClientContext = new ServerClientContext(clientKey, packetContext, HikariStatus.HIKARI_AUTH);
            clientKey.attach(serverClientContext);
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
                writeHikariFail(HikariProtocol.AUTH_RESPONSE_CONNECT_TARGET_FAIL, clientChannel, serverTargetContext);
                return;
            }

            selectionKey.interestOps(SelectionKey.OP_READ);

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

            // write
            encryptWrite(dataBuffer, clientChannel);

            // set status
            serverClientContext.setStatus(HikariStatus.HIKARI_PROXY);
        } catch (Exception e) {
            String msg = e.getMessage();
            logger.warn("handle connect exception: {}", msg != null ? msg : e.getClass().getName());
            serverTargetContext.close();
        }
    }

    @Override
    public void consumeData(final SelectionKey selectionKey,
                            final SocketChannel socketChannel,
                            final ByteBuffer data,
                            final HikariContext hikariContext) throws Exception {
        final ServerContext serverContext = (ServerContext) hikariContext;
        final ServerContextType type = serverContext.getType();

        if (type == ServerContextType.CLIENT) {
            final ServerClientContext serverClientContext = (ServerClientContext) serverContext;
            final HikariStatus status = serverClientContext.getStatus();

            switch (status) {
                case HIKARI_AUTH:
                    processHikariAuthRead(socketChannel, data, serverClientContext);
                    break;

                case HIKARI_PROXY:
                    processHikariProxyRead(socketChannel, data, serverClientContext);
                    break;

                default:
                    throw new HikariRuntimeException(String.format("server status '%s' not supported", status.name()));
            }
        }
        else if (type == ServerContextType.TARGET) {
            final ServerTargetContext serverTargetContext = (ServerTargetContext) serverContext;

            processTargetRead(socketChannel, data, serverTargetContext);
        }
        else {
            throw new HikariRuntimeException(String.format("server context type '%s' not supported", type.name()));
        }
    }

    private void processHikariAuthRead(final SocketChannel socketChannel, final ByteBuffer data, final ServerClientContext serverClientContext) throws IOException {
        // ver
        final byte ver = data.get();
        if (ver != HikariProtocol.VERSION_HIKARI1) {
            writeHikariFail(HikariProtocol.AUTH_RESPONSE_VERSION_NOT_SUPPORT, socketChannel, serverClientContext);
            return;
        }

        // auth
        final byte[] auth = new byte[16];
        data.get(auth);
        String keyHashHex = HexUtil.hexString(auth);
        if (!privateKeyHashSet.contains(keyHashHex)) {
            writeHikariFail(HikariProtocol.AUTH_RESPONSE_AUTH_FAIL, socketChannel, serverClientContext);
            return;
        }

        // address
        final byte hikariAddressType = data.get();
        final byte[] address;
        if (hikariAddressType == HikariProtocol.ADDRESS_TYPE_DOMAIN) {
            // resolve
            int length = data.get();
            byte[] tmpArray = new byte[length];
            data.get(tmpArray, 0, length);

            String domainName = new String(tmpArray, StandardCharsets.US_ASCII);
            InetAddress inetAddress;
            try {
                inetAddress = InetAddress.getByName(domainName);
            } catch (UnknownHostException e) {
                logger.error("DNS resolve fail: {}", domainName);
                writeHikariFail(HikariProtocol.AUTH_RESPONSE_DNS_RESOLVE_FAIL, socketChannel, serverClientContext);
                return;
            }

            address = inetAddress.getAddress();
        }
        else if (hikariAddressType == HikariProtocol.ADDRESS_TYPE_IPV4) {
            address = new byte[4];
            data.get(address, 0, 4);
        }
        else if (hikariAddressType == HikariProtocol.ADDRESS_TYPE_IPV6) {
            address = new byte[16];
            data.get(address, 0, 16);
        }
        else {
            throw new HikariRuntimeException(String.format("hikari address type '%s' not supported", hikariAddressType));
        }

        // port
        final short port = data.getShort();

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

    private void processHikariProxyRead(final SocketChannel socketChannel, final ByteBuffer data, final ServerClientContext serverClientContext) throws IOException {
        final SocketChannel targetChannel = (SocketChannel) serverClientContext.getTargetContext().getSelectionKey().channel();
        targetChannel.write(data);
    }

    private void processTargetRead(final SocketChannel socketChannel, final ByteBuffer data, final ServerTargetContext serverTargetContext) throws IOException {
        final SocketChannel clientChannel = (SocketChannel) serverTargetContext.getClientContext().getSelectionKey().channel();
        encryptWrite(data, clientChannel);
    }

    private void writeHikariFail(final byte response, final SocketChannel socketChannel, final ServerContext serverContext) throws IOException {
        dataBuffer.clear();
        dataBuffer.put(HikariProtocol.VERSION_HIKARI1);
        dataBuffer.put(response);
        dataBuffer.flip();

        encryptWrite(dataBuffer, socketChannel);
        serverContext.close();
    }
}
