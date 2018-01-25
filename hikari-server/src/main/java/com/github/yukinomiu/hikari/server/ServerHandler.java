package com.github.yukinomiu.hikari.server;

import com.github.yukinomiu.hikari.common.HikariHandle;
import com.github.yukinomiu.hikari.common.HikariStatus;
import com.github.yukinomiu.hikari.common.constants.HikariConstants;
import com.github.yukinomiu.hikari.common.exception.HikariRuntimeException;
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
    private final ByteBuffer buffer;
    private final Set<String> privateKeyHashSet;

    public ServerHandler(final ServerConfig serverConfig) {
        this.serverConfig = serverConfig;

        // init buffer
        Integer bufferSize = serverConfig.getBufferSize();
        buffer = ByteBuffer.allocateDirect(bufferSize);

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

            final ServerClientContext context = new ServerClientContext();
            SelectionKey newKey = socketChannel.register(selector, SelectionKey.OP_READ, context);

            context.setSelectionKey(newKey);
            context.setStatus(HikariStatus.HIKARI_AUTH);
        } catch (Exception e) {
            logger.warn("handle accept exception: {}", e);
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
                writeAuthFailResponse(buffer, HikariConstants.AUTH_RESPONSE_CONNECT_TARGET_FAIL, clientChannel, serverTargetContext);
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
                bindHikariAddressType = HikariConstants.ADDRESS_TYPE_IPV4;
            }
            else if (localAddress instanceof Inet6Address) {
                bindHikariAddressType = HikariConstants.ADDRESS_TYPE_IPV6;
            }
            else {
                throw new HikariRuntimeException(String.format("address type '%s' not supported", localAddress.toString()));
            }

            buffer.put(HikariConstants.VERSION_HIKARI1);
            buffer.put(HikariConstants.AUTH_RESPONSE_OK);
            buffer.put(bindHikariAddressType);
            buffer.put(bindAddress);
            buffer.putShort(port);

            buffer.flip();

            clientChannel.write(buffer);

            // set status
            serverClientContext.setStatus(HikariStatus.HIKARI_PROXY);
        } catch (Exception e) {
            logger.warn("handle connect exception: {}", e);
            serverTargetContext.close();
        } finally {
            buffer.clear();
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
            logger.warn("handle read exception: {}", e);
            serverContext.close();
        } finally {
            buffer.clear();
        }
    }

    private void processHikariAuthRead(final SocketChannel socketChannel, final ServerClientContext serverClientContext) throws IOException {
        socketChannel.read(buffer);
        buffer.flip();

        // version
        final byte version = buffer.get();
        if (version != HikariConstants.VERSION_HIKARI1) {
            writeAuthFailResponse(buffer, HikariConstants.AUTH_RESPONSE_VERSION_NOT_SUPPORT, socketChannel, serverClientContext);
            return;
        }

        // auth
        final byte[] auth = new byte[16];
        buffer.get(auth);
        String keyHashHex = Hex.encodeHexString(auth);
        if (!privateKeyHashSet.contains(keyHashHex)) {
            writeAuthFailResponse(buffer, HikariConstants.AUTH_RESPONSE_AUTH_FAIL, socketChannel, serverClientContext);
            return;
        }

        // encrypt type
        final byte encryptType = buffer.get();
        if (encryptType != HikariConstants.ENCRYPT_PLAIN) {
            writeAuthFailResponse(buffer, HikariConstants.AUTH_RESPONSE_ENCRYPT_TYPE_NOT_SUPPORT, socketChannel, serverClientContext);
            return;
        }

        // address
        final byte hikariAddressType = buffer.get();
        final byte[] address;
        if (hikariAddressType == HikariConstants.ADDRESS_TYPE_DOMAIN) {
            // resolve
            int length = buffer.get();
            byte[] tmpArray = new byte[length];
            buffer.get(tmpArray, 0, length);

            String domainName = new String(tmpArray, StandardCharsets.US_ASCII);
            InetAddress inetAddress;
            try {
                inetAddress = InetAddress.getByName(domainName);
            } catch (UnknownHostException e) {
                logger.error("DNS resolve fail: {}", domainName);
                writeAuthFailResponse(buffer, HikariConstants.AUTH_RESPONSE_DNS_RESOLVE_FAIL, socketChannel, serverClientContext);
                return;
            }

            address = inetAddress.getAddress();
        }
        else if (hikariAddressType == HikariConstants.ADDRESS_TYPE_IPV4) {
            address = new byte[4];
            buffer.get(address, 0, 4);
        }
        else if (hikariAddressType == HikariConstants.ADDRESS_TYPE_IPV6) {
            address = new byte[16];
            buffer.get(address, 0, 16);
        }
        else {
            throw new HikariRuntimeException(String.format("hikari address type '%s' not supported", hikariAddressType));
        }

        // port
        final short port = buffer.getShort();

        // connect to target
        InetAddress inetAddress = InetAddress.getByAddress(address);

        final SelectionKey clientKey = serverClientContext.getSelectionKey();
        final Selector selector = clientKey.selector();
        final SocketAddress targetAddress = new InetSocketAddress(inetAddress, port);

        SocketChannel targetChannel = SocketChannel.open();
        targetChannel.configureBlocking(false);

        ServerTargetContext targetContext = new ServerTargetContext();
        final SelectionKey targetKey = targetChannel.register(selector, SelectionKey.OP_CONNECT, targetContext);

        targetContext.setSelectionKey(targetKey);
        targetContext.setClientContext(serverClientContext);

        serverClientContext.setTargetContext(targetContext);

        boolean connectedNow = targetChannel.connect(targetAddress);
        if (connectedNow) {
            buffer.clear();
            handleConnect(targetKey);
        }
    }

    private void processHikariProxyRead(final SocketChannel socketChannel, final ServerClientContext serverClientContext) throws IOException {
        int read = socketChannel.read(buffer);
        if (read == -1) {
            serverClientContext.close();
            return;
        }
        else if (read == 0) {
            return;
        }

        final SocketChannel targetChannel = (SocketChannel) serverClientContext.getTargetContext().getSelectionKey().channel();
        buffer.flip();
        targetChannel.write(buffer);
    }

    private void processTargetRead(SocketChannel socketChannel, ServerTargetContext serverTargetContext) throws IOException {
        int read = socketChannel.read(buffer);
        if (read == -1) {
            serverTargetContext.close();
            return;
        }
        else if (read == 0) {
            return;
        }

        final SocketChannel clientChannel = (SocketChannel) serverTargetContext.getClientContext().getSelectionKey().channel();
        buffer.flip();
        clientChannel.write(buffer);
    }

    private void writeAuthFailResponse(final ByteBuffer buffer, final byte response, final SocketChannel socketChannel, final ServerContext serverContext) throws IOException {
        buffer.clear();
        buffer.put(HikariConstants.VERSION_HIKARI1);
        buffer.put(response);
        buffer.flip();

        socketChannel.write(buffer);
        serverContext.close();
    }
}
