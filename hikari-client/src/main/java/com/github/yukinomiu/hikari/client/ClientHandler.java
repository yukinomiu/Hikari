package com.github.yukinomiu.hikari.client;

import com.github.yukinomiu.hikari.common.*;
import com.github.yukinomiu.hikari.common.exception.HikariRuntimeException;
import com.github.yukinomiu.hikari.common.protocol.HikariProtocol;
import com.github.yukinomiu.hikari.common.protocol.Socks4Protocol;
import com.github.yukinomiu.hikari.common.protocol.Socks5Protocol;
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
import java.util.List;

/**
 * Yukinomiu
 * 2018/1/22
 */
public class ClientHandler extends HikariAbstractHandle {
    private static final Logger logger = LoggerFactory.getLogger(ClientHandler.class);
    private final ClientConfig config;

    private final Integer bufferSize;
    private final ByteBuffer dataBuffer;
    private final ByteBuffer cacheBuffer;
    private final ByteBuffer cryptoBuffer;
    private final ByteBuffer packetBuffer;

    private final SocketAddress[] serverAddressArray;
    private final int maxAddressIndex;
    private int currentAddressIndex;

    private final byte[] privateKeyHash;

    public ClientHandler(final ClientConfig config) {
        super(config);

        // config
        this.config = config;

        // buffer
        bufferSize = config.getBufferSize();
        dataBuffer = ByteBuffer.allocateDirect(bufferSize);
        cacheBuffer = ByteBuffer.allocateDirect(bufferSize << 1);
        cryptoBuffer = ByteBuffer.allocateDirect(bufferSize);
        packetBuffer = ByteBuffer.allocateDirect(bufferSize + HikariConstant.PACKET_WRAPPER_SIZE);

        // server address
        String serverAddress = config.getServerAddress();
        List<Integer> serverPortList = config.getServerPortList();
        logger.info("server address: {}", serverAddress);

        serverAddressArray = new SocketAddress[serverPortList.size()];
        for (int i = 0; i < serverAddressArray.length; i++) {
            final Integer port = serverPortList.get(i);
            SocketAddress address = new InetSocketAddress(serverAddress, port);
            serverAddressArray[i] = address;
            logger.info("server port: {}", port);
        }
        maxAddressIndex = serverAddressArray.length - 1;
        currentAddressIndex = 0;

        // private key
        String privateKey = config.getPrivateKey();
        privateKeyHash = Md5Util.getInstance().md5(privateKey);
    }

    @Override
    public void handleAccept(final SelectionKey key) {
        final Selector selector = key.selector();
        final ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();

        try {
            SocketChannel channel = serverChannel.accept();
            channel.configureBlocking(false);

            SelectionKey localKey = channel.register(selector, SelectionKey.OP_READ);

            ClientLocalContext localContext = new ClientLocalContext(localKey, bufferSize, SocksStatus.SOCKS_NEW);
            localKey.attach(localContext);
        } catch (Exception e) {
            String msg = e.getMessage();
            logger.warn("handle accept exception: {}", msg != null ? msg : e.getClass().getName());
        }
    }

    @Override
    public void handleConnect(final SelectionKey key) {
        final ClientRemoteContext remoteContext = (ClientRemoteContext) key.attachment();
        final ClientLocalContext localContext = remoteContext.getLocalContext();
        final SocketChannel remoteChannel = (SocketChannel) key.channel();
        final SocketChannel localChannel = (SocketChannel) localContext.key().channel();

        try {
            try {
                remoteChannel.finishConnect();
            } catch (IOException e) {
                logger.warn("connect to server fail, msg: {}", e.getMessage());

                // response
                final byte ver = localContext.getSocksVersion();

                if (ver == Socks5Protocol.VERSION_SOCKS5) {
                    writeSocks5Fail(Socks5Protocol.REQ_REPLAY_CONNECTION_REFUSED, localChannel, remoteContext);
                }
                else if (ver == Socks4Protocol.VERSION_SOCKS4) {
                    writeSocks4Fail(Socks4Protocol.REQ_REPLAY_REJECTED_OR_FAILED, localChannel, remoteContext);
                }
                else {
                    logger.warn("socks version '{}' not supported", ver);
                    remoteContext.close();
                }

                return;
            }

            // read read
            key.interestOps(SelectionKey.OP_READ);

            // request
            sendHikariRequest(localContext, remoteContext);
        } catch (Exception e) {
            String msg = e.getMessage();
            logger.warn("handle connect exception: {}", msg != null ? msg : e.getClass().getName());
            remoteContext.close();
        }
    }

    @Override
    public void handleRead(final SelectionKey key) {
        final ClientContext context = (ClientContext) key.attachment();
        final ClientContextType type = context.getType();

        try {
            if (type == ClientContextType.LOCAL) {
                final ClientLocalContext localContext = (ClientLocalContext) context;
                final SocksStatus status = localContext.getStatus();

                switch (status) {
                    case SOCKS_NEW:
                        processSocksNew(key, localContext);
                        break;

                    case SOCKS5_REQ:
                        processSocks5ReqRead(key, localContext);
                        break;

                    case SOCKS_PROXY:
                        processSocksProxyRead(key, localContext);
                        break;

                    default:
                        throw new HikariRuntimeException(String.format("socks status '%s' not supported", status.name()));
                }

            }
            else if (type == ClientContextType.REMOTE) {
                final ClientRemoteContext remoteContext = (ClientRemoteContext) context;
                final HikariStatus status = remoteContext.getStatus();

                switch (status) {
                    case HIKARI_AUTH:
                        processHikariAuthRead(key, remoteContext);
                        break;

                    case HIKARI_PROXY:
                        processHikariProxyRead(key, remoteContext);
                        break;

                    default:
                        throw new HikariRuntimeException(String.format("client hikari status '%s' not supported", status.name()));
                }
            }
            else {
                throw new HikariRuntimeException(String.format("client context type '%s' not supported", type.name()));
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            logger.warn("handle read exception: {}", msg != null ? msg : e.getClass().getName());
            context.close();
        }
    }

    @Override
    public void handleWrite(final SelectionKey key) {
        final ClientContext context = (ClientContext) key.attachment();
        final ClientContextType type = context.getType();

        try {
            if (type == ClientContextType.LOCAL) {
                final ClientLocalContext localContext = (ClientLocalContext) context;
                final SocketChannel localChannel = (SocketChannel) key.channel();
                final ByteBuffer writeBuffer = localContext.writeBuffer();

                localChannel.write(writeBuffer);
                if (!writeBuffer.hasRemaining()) {
                    final SelectionKey remoteKey = localContext.getRemoteContext().key();

                    key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                    remoteKey.interestOps(remoteKey.interestOps() | SelectionKey.OP_READ);
                }
            }
            else if (type == ClientContextType.REMOTE) {
                final ClientRemoteContext remoteContext = (ClientRemoteContext) context;
                final SocketChannel remoteChannel = (SocketChannel) key.channel();
                final ByteBuffer writeBuffer = remoteContext.writeBuffer();

                remoteChannel.write(writeBuffer);
                if (!writeBuffer.hasRemaining()) {
                    final SelectionKey localKey = remoteContext.getLocalContext().key();

                    key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                    localKey.interestOps(localKey.interestOps() | SelectionKey.OP_READ);
                }
            }
            else {
                throw new HikariRuntimeException(String.format("client context type '%s' not supported", type.name()));
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            logger.warn("handle write exception: {}", msg != null ? msg : e.getClass().getName());
            context.close();
        }
    }

    private void processSocksNew(final SelectionKey key,
                                 final ClientLocalContext localContext) throws IOException {
        final SocketChannel localChannel = (SocketChannel) key.channel();
        if (!read(localChannel, dataBuffer, localContext)) {
            return;
        }

        // ver
        final byte ver = dataBuffer.get();

        // set context
        localContext.setSocksVersion(ver);

        if (ver == Socks5Protocol.VERSION_SOCKS5) {
            processSocks5AuthRead(key, localContext, dataBuffer);
        }
        else if (ver == Socks4Protocol.VERSION_SOCKS4) {
            processSocks4ReqRead(key, localContext, dataBuffer);
        }
        else {
            logger.warn("socks version '{}' not supported", ver);
            localContext.close();
        }
    }

    private void processSocks5AuthRead(final SelectionKey key,
                                       final ClientLocalContext localContext,
                                       final ByteBuffer dataBuffer) throws IOException {
        final byte methods = dataBuffer.get();
        if (dataBuffer.remaining() != methods) {
            logger.warn("bad socks auth request");
            localContext.close();
            return;
        }

        // response
        dataBuffer.clear();
        dataBuffer.put(Socks5Protocol.VERSION_SOCKS5);
        dataBuffer.put(Socks5Protocol.AUTH_METHOD_NO_AUTH);
        dataBuffer.flip();

        // write
        final SocketChannel localChannel = (SocketChannel) key.channel();
        localChannel.write(dataBuffer);
        if (dataBuffer.hasRemaining()) {
            logger.warn("send socks auth response fail");
            localContext.close();
            return;
        }

        // set status
        localContext.setStatus(SocksStatus.SOCKS5_REQ);
    }

    private void processSocks5ReqRead(final SelectionKey key,
                                      final ClientLocalContext localContext) throws IOException {
        final SocketChannel localChannel = (SocketChannel) key.channel();
        if (!read(localChannel, dataBuffer, localContext)) {
            return;
        }

        // cancel
        key.interestOps(0);

        // ver
        dataBuffer.get();

        // command
        final byte command = dataBuffer.get();
        if (command != Socks5Protocol.REQ_COMMAND_CONNECT) {
            writeSocks5Fail(Socks5Protocol.REQ_REPLAY_COMMAND_NOT_SUPPORTED, localChannel, localContext);
            return;
        }

        // rsv
        dataBuffer.get();

        // address
        final byte addressType = dataBuffer.get();
        final byte hikariAddressType;
        final byte[] address;

        if (addressType == Socks5Protocol.ADDRESS_TYPE_DOMAIN) {
            int length = dataBuffer.get();
            byte[] domainByteArray = new byte[length];
            dataBuffer.get(domainByteArray, 0, length);

            if (config.getLocalDnsResolve()) {
                // local dns resolve
                InetAddress inetAddress;
                final String domain = new String(domainByteArray, StandardCharsets.UTF_8);
                try {
                    inetAddress = InetAddress.getByName(domain);
                } catch (UnknownHostException e) {
                    logger.warn("DNS resolve fail: {}", domain);
                    writeSocks5Fail(Socks5Protocol.REQ_REPLAY_HOST_UNREACHABLE, localChannel, localContext);
                    return;
                }

                if (inetAddress instanceof Inet4Address) {
                    hikariAddressType = HikariProtocol.ADDRESS_TYPE_IPV4;
                    address = inetAddress.getAddress();
                }
                else if (inetAddress instanceof Inet6Address) {
                    hikariAddressType = HikariProtocol.ADDRESS_TYPE_IPV6;
                    address = inetAddress.getAddress();
                }
                else {
                    throw new HikariRuntimeException(String.format("address type '%s' not supported", inetAddress.toString()));
                }
            }
            else {
                hikariAddressType = HikariProtocol.ADDRESS_TYPE_DOMAIN;
                address = domainByteArray;
            }
        }
        else if (addressType == Socks5Protocol.ADDRESS_TYPE_IPV4) {
            address = new byte[4];
            dataBuffer.get(address, 0, 4);

            hikariAddressType = HikariProtocol.ADDRESS_TYPE_IPV4;
        }
        else if (addressType == Socks5Protocol.ADDRESS_TYPE_IPV6) {
            address = new byte[16];
            dataBuffer.get(address, 0, 16);

            hikariAddressType = HikariProtocol.ADDRESS_TYPE_IPV6;
        }
        else {
            writeSocks5Fail(Socks5Protocol.REQ_REPLAY_ADDRESS_TYPE_NOT_SUPPORTED, localChannel, localContext);
            return;
        }

        // port
        final byte[] port = new byte[2];
        dataBuffer.get(port, 0, 2);

        if (dataBuffer.hasRemaining()) {
            logger.warn("bad socks req request");
            localContext.close();
            return;
        }

        // set context
        localContext.setHikariAddressType(hikariAddressType);
        localContext.setAddress(address);
        localContext.setPort(port);

        // connection to server
        final SocketAddress serverAddress = getServerAddress();
        final SelectionKey localKey = localContext.key();
        final Selector selector = localKey.selector();

        SocketChannel remoteChannel = SocketChannel.open();
        remoteChannel.configureBlocking(false);

        final SelectionKey remoteKey = remoteChannel.register(selector, SelectionKey.OP_CONNECT);

        ClientRemoteContext remoteContext = new ClientRemoteContext(remoteKey, bufferSize, HikariStatus.HIKARI_AUTH, localContext);
        remoteKey.attach(remoteContext);

        localContext.setRemoteContext(remoteContext);

        boolean connectedNow = remoteChannel.connect(serverAddress);
        if (connectedNow) {
            handleConnect(remoteKey);
        }
    }

    private void processSocks4ReqRead(final SelectionKey key,
                                      final ClientLocalContext localContext,
                                      final ByteBuffer dataBuffer) throws IOException {
        final SocketChannel localChannel = (SocketChannel) key.channel();

        // cancel
        key.interestOps(0);

        // command
        final byte command = dataBuffer.get();
        if (command != Socks4Protocol.REQ_COMMAND_CONNECT) {
            writeSocks4Fail(Socks4Protocol.REQ_REPLAY_REJECTED_OR_FAILED, localChannel, localContext);
            return;
        }

        // port
        final byte[] port = new byte[2];
        dataBuffer.get(port, 0, 2);

        // address
        final byte[] address = new byte[4];
        dataBuffer.get(address, 0, 4);

        // ignore user id

        // null
        dataBuffer.position(dataBuffer.limit() - 1);
        final byte end = dataBuffer.get();
        if (end != Socks4Protocol.REQ_REPLAY_NULL) {
            logger.warn("bad socks req request");
            localContext.close();
            return;
        }

        // set context
        localContext.setHikariAddressType(HikariProtocol.ADDRESS_TYPE_IPV4);
        localContext.setAddress(address);
        localContext.setPort(port);

        // connection to server
        final SocketAddress serverAddress = getServerAddress();
        final SelectionKey localKey = localContext.key();
        final Selector selector = localKey.selector();

        SocketChannel remoteChannel = SocketChannel.open();
        remoteChannel.configureBlocking(false);

        final SelectionKey remoteKey = remoteChannel.register(selector, SelectionKey.OP_CONNECT);

        ClientRemoteContext remoteContext = new ClientRemoteContext(remoteKey, bufferSize, HikariStatus.HIKARI_AUTH, localContext);
        remoteKey.attach(remoteContext);

        localContext.setRemoteContext(remoteContext);

        boolean connectedNow = remoteChannel.connect(serverAddress);
        if (connectedNow) {
            handleConnect(remoteKey);
        }
    }

    private void processSocksProxyRead(final SelectionKey key,
                                       final ClientLocalContext localContext) throws IOException {
        final SocketChannel localChannel = (SocketChannel) key.channel();
        if (!read(localChannel, dataBuffer, localContext)) {
            return;
        }

        // encrypt
        encrypt(dataBuffer, cryptoBuffer, packetBuffer);

        // write
        final ClientRemoteContext remoteContext = localContext.getRemoteContext();
        write(localContext, remoteContext, packetBuffer);
    }

    private void processHikariAuthRead(final SelectionKey key,
                                       final ClientRemoteContext remoteContext) throws IOException {
        final SocketChannel remoteChannel = (SocketChannel) key.channel();
        if (!read(remoteChannel, packetBuffer, remoteContext)) {
            return;
        }

        final ClientLocalContext localContext = remoteContext.getLocalContext();
        final SocketChannel localChannel = (SocketChannel) localContext.key().channel();
        final PacketContext packetContext = remoteContext.getPacketContext();

        // decrypt
        cacheBuffer.clear();
        while (decrypt(packetBuffer, cryptoBuffer, dataBuffer, packetContext)) {
            cacheBuffer.put(dataBuffer);
        }
        cacheBuffer.flip();

        // ver
        cacheBuffer.get();

        // reply
        final byte reply = cacheBuffer.get();

        switch (reply) {
            case HikariProtocol.AUTH_RESPONSE_OK:
                // bind address type and address
                final byte bindHikariAddressType = cacheBuffer.get();
                final byte socks5AddressType;
                final byte[] bindAddress;

                if (bindHikariAddressType == HikariProtocol.ADDRESS_TYPE_IPV4) {
                    socks5AddressType = Socks5Protocol.ADDRESS_TYPE_IPV4;
                    bindAddress = new byte[4];
                    cacheBuffer.get(bindAddress, 0, 4);
                }
                else if (bindHikariAddressType == HikariProtocol.ADDRESS_TYPE_IPV6) {
                    socks5AddressType = Socks5Protocol.ADDRESS_TYPE_IPV6;
                    bindAddress = new byte[16];
                    cacheBuffer.get(bindAddress, 0, 16);
                }
                else {
                    logger.warn("hikari address type '%s' not supported", bindHikariAddressType);
                    remoteContext.close();
                    return;
                }

                // bind port
                final byte[] bindPort = new byte[2];
                cacheBuffer.get(bindPort, 0, 2);

                // response
                final byte ver = localContext.getSocksVersion();
                if (ver == Socks5Protocol.VERSION_SOCKS5) {
                    dataBuffer.clear();
                    dataBuffer.put(Socks5Protocol.VERSION_SOCKS5);
                    dataBuffer.put(Socks5Protocol.REQ_REPLAY_SUCCEEDED);
                    dataBuffer.put((byte) 0x00);
                    dataBuffer.put(socks5AddressType);
                    dataBuffer.put(bindAddress);
                    dataBuffer.put(bindPort);
                    dataBuffer.flip();
                    localChannel.write(dataBuffer);
                }
                else if (ver == Socks4Protocol.VERSION_SOCKS4) {
                    dataBuffer.clear();
                    dataBuffer.put(Socks4Protocol.REQ_REPLAY_VN);
                    dataBuffer.put(Socks4Protocol.REQ_REPLAY_GRANTED);
                    dataBuffer.put(localContext.getPort());
                    dataBuffer.put(localContext.getAddress());
                    dataBuffer.flip();
                    localChannel.write(dataBuffer);
                }
                else {
                    logger.warn("socks version '{}' not supported", ver);
                    remoteContext.close();
                    return;
                }

                if (dataBuffer.hasRemaining()) {
                    logger.warn("send socks req response fail");
                    remoteContext.close();
                    return;
                }

                // left data
                if (cacheBuffer.hasRemaining()) {
                    dataBuffer.clear();
                    dataBuffer.put(cacheBuffer);
                    dataBuffer.flip();
                    localChannel.write(dataBuffer);

                    if (dataBuffer.hasRemaining()) {
                        logger.warn("send left data fail");
                        remoteContext.close();
                        return;
                    }
                }

                // set status
                localContext.setStatus(SocksStatus.SOCKS_PROXY);
                remoteContext.setStatus(HikariStatus.HIKARI_PROXY);

                // open
                localContext.key().interestOps(SelectionKey.OP_READ);
                break;

            case HikariProtocol.AUTH_RESPONSE_VERSION_NOT_SUPPORT:
                logger.warn("server: hikari version not supported");
                writeSocks5Fail(Socks5Protocol.REQ_REPLAY_GENERAL_FAILURE, localChannel, remoteContext);
                break;

            case HikariProtocol.AUTH_RESPONSE_AUTH_FAIL:
                logger.warn("server: auth fail");
                writeSocks5Fail(Socks5Protocol.REQ_REPLAY_GENERAL_FAILURE, localChannel, remoteContext);
                break;

            case HikariProtocol.AUTH_RESPONSE_DNS_RESOLVE_FAIL:
                byte[] address = localContext.getAddress();
                String domainName = new String(address, StandardCharsets.US_ASCII);

                logger.warn("server: DNS resolve fail, domain name: {}", domainName);
                writeSocks5Fail(Socks5Protocol.REQ_REPLAY_HOST_UNREACHABLE, localChannel, remoteContext);
                break;

            case HikariProtocol.AUTH_RESPONSE_CONNECT_TARGET_FAIL:
                logger.warn("server: connect to target fail");
                writeSocks5Fail(Socks5Protocol.REQ_REPLAY_NETWORK_UNREACHABLE, localChannel, remoteContext);
                break;

            default:
                logger.warn("bad server response, reply: {}", reply);
                writeSocks5Fail(Socks5Protocol.REQ_REPLAY_GENERAL_FAILURE, localChannel, remoteContext);
                break;
        }
    }

    private void processHikariProxyRead(final SelectionKey key,
                                        final ClientRemoteContext remoteContext) throws IOException {
        final SocketChannel remoteChannel = (SocketChannel) key.channel();

        if (!read(remoteChannel, packetBuffer, remoteContext)) {
            return;
        }

        // decrypt
        final PacketContext packetContext = remoteContext.getPacketContext();
        final ClientLocalContext localContext = remoteContext.getLocalContext();

        cacheBuffer.clear();
        while (decrypt(packetBuffer, cryptoBuffer, dataBuffer, packetContext)) {
            cacheBuffer.put(dataBuffer);
        }
        cacheBuffer.flip();

        // write
        write(remoteContext, localContext, cacheBuffer);
    }

    private void writeSocks4Fail(final byte rsp,
                                 final SocketChannel channel,
                                 final ClientContext context) throws IOException {
        dataBuffer.clear();
        dataBuffer.put(Socks4Protocol.REQ_REPLAY_VN);
        dataBuffer.put(rsp);
        dataBuffer.flip();

        // write
        channel.write(dataBuffer);
        if (dataBuffer.hasRemaining()) {
            logger.warn("send socks req response fail");
        }

        context.close();
    }

    private void writeSocks5Fail(final byte rsp,
                                 final SocketChannel channel,
                                 final ClientContext context) throws IOException {
        dataBuffer.clear();
        dataBuffer.put(Socks5Protocol.VERSION_SOCKS5);
        dataBuffer.put(rsp);
        dataBuffer.flip();

        // write
        channel.write(dataBuffer);
        if (dataBuffer.hasRemaining()) {
            logger.warn("send socks req response fail");
        }

        context.close();
    }

    private void sendHikariRequest(final ClientLocalContext localContext, final ClientRemoteContext remoteContext) throws IOException {
        final SocketChannel remoteChannel = (SocketChannel) remoteContext.key().channel();

        final byte hikariAddressType = localContext.getHikariAddressType();
        final byte[] address = localContext.getAddress();
        final byte[] port = localContext.getPort();

        // request
        dataBuffer.clear();
        dataBuffer.put(HikariProtocol.VERSION_HIKARI1);
        dataBuffer.put(privateKeyHash);
        dataBuffer.put(hikariAddressType);
        if (hikariAddressType == HikariProtocol.ADDRESS_TYPE_DOMAIN) {
            dataBuffer.put((byte) address.length);
        }
        dataBuffer.put(address);
        dataBuffer.put(port);
        dataBuffer.flip();

        // encrypt
        encrypt(dataBuffer, cryptoBuffer, packetBuffer);

        // write
        remoteChannel.write(packetBuffer);
        if (packetBuffer.hasRemaining()) {
            logger.warn("send hikari auth request fail");
            remoteContext.close();
        }
    }

    private SocketAddress getServerAddress() {
        SocketAddress serverAddress = serverAddressArray[currentAddressIndex++];
        if (currentAddressIndex > maxAddressIndex) {
            currentAddressIndex = 0;
        }

        return serverAddress;
    }
}
