package com.github.yukinomiu.hikari.client;

import com.github.yukinomiu.hikari.common.*;
import com.github.yukinomiu.hikari.common.crypto.AESCrypto;
import com.github.yukinomiu.hikari.common.crypto.HikariCrypto;
import com.github.yukinomiu.hikari.common.exception.HikariChecksumFailException;
import com.github.yukinomiu.hikari.common.exception.HikariRuntimeException;
import com.github.yukinomiu.hikari.common.protocol.HikariProtocol;
import com.github.yukinomiu.hikari.common.protocol.SocksProtocol;
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

    private final ClientConfig clientConfig;
    private final HikariCrypto hikariCrypto;

    private final ByteBuffer dataBuffer;
    private final ByteBuffer cryptoBuffer;
    private final ByteBuffer packetBuffer;

    private final SocketAddress[] serverAddressArray;
    private final int maxAddressIndex;
    private int currentAddressIndex;

    private final byte[] privateKeyHash;

    public ClientHandler(ClientConfig clientConfig) {
        // config
        this.clientConfig = clientConfig;

        // crypto
        hikariCrypto = new AESCrypto("secret");

        // buffer
        final Integer bufferSize = clientConfig.getBufferSize();
        dataBuffer = ByteBuffer.allocateDirect(bufferSize);
        cryptoBuffer = ByteBuffer.allocateDirect(bufferSize);
        packetBuffer = ByteBuffer.allocateDirect(bufferSize + HikariConstant.PACKET_WRAPPER_SIZE);

        // server address
        String serverAddress = clientConfig.getServerAddress();
        List<Integer> serverPortList = clientConfig.getServerPortList();

        serverAddressArray = new SocketAddress[serverPortList.size()];
        for (int i = 0; i < serverAddressArray.length; i++) {
            final Integer port = serverPortList.get(i);
            SocketAddress address = new InetSocketAddress(serverAddress, port);
            serverAddressArray[i] = address;
        }
        maxAddressIndex = serverAddressArray.length - 1;
        currentAddressIndex = 0;

        // private key
        String privateKey = clientConfig.getPrivateKey();
        privateKeyHash = Md5Util.getInstance().md5(privateKey);
    }

    @Override
    public void handleAccept(final SelectionKey selectionKey) {
        final Selector selector = selectionKey.selector();
        final ServerSocketChannel serverSocketChannel = (ServerSocketChannel) selectionKey.channel();

        try {
            SocketChannel socketChannel = serverSocketChannel.accept();
            socketChannel.configureBlocking(false);

            SelectionKey localKey = socketChannel.register(selector, SelectionKey.OP_READ);

            ClientLocalContext clientLocalContext = new ClientLocalContext(localKey, SocksStatus.SOCKS_AUTH);
            localKey.attach(clientLocalContext);
        } catch (Exception e) {
            logger.warn("handle accept exception: {}", e.getMessage(), e);
        }
    }

    @Override
    public void handleConnect(final SelectionKey selectionKey) {
        final ClientRemoteContext clientRemoteContext = (ClientRemoteContext) selectionKey.attachment();
        final ClientLocalContext clientLocalContext = clientRemoteContext.getLocalContext();

        final SocketChannel remoteChannel = (SocketChannel) selectionKey.channel();
        final SocketChannel localChannel = (SocketChannel) clientLocalContext.getSelectionKey().channel();

        try {
            try {
                remoteChannel.finishConnect();
            } catch (IOException e) {
                logger.warn("connect to server fail, msg: {}", e.getMessage());
                writeSocksFail(SocksProtocol.REQ_REPLAY_HOST_UNREACHABLE, localChannel, clientRemoteContext);
                return;
            }

            selectionKey.interestOps(SelectionKey.OP_READ);

            // request
            final byte hikariAddressType = clientLocalContext.getHikariAddressType();
            final byte[] address = clientLocalContext.getAddress();
            final byte[] port = clientLocalContext.getPort();

            dataBuffer.clear();
            dataBuffer.put(HikariProtocol.VERSION_HIKARI1);
            dataBuffer.put(privateKeyHash);
            dataBuffer.put(HikariProtocol.ENCRYPT_PLAIN);
            dataBuffer.put(hikariAddressType);
            if (hikariAddressType == HikariProtocol.ADDRESS_TYPE_DOMAIN) {
                dataBuffer.put((byte) address.length);
            }
            dataBuffer.put(address);
            dataBuffer.put(port);
            dataBuffer.flip();

            // encrypt
            cryptoBuffer.clear();
            encrypt(dataBuffer, cryptoBuffer, hikariCrypto);
            cryptoBuffer.flip();

            // wrap packet
            packetBuffer.clear();
            wrapPacket(cryptoBuffer, packetBuffer);
            packetBuffer.flip();

            // write
            remoteChannel.write(packetBuffer);
        } catch (Exception e) {
            logger.warn("handle connect exception: {}", e.getMessage(), e);
            clientRemoteContext.close();
        }
    }

    @Override
    public void handleRead(final SelectionKey selectionKey) {
        final SocketChannel socketChannel = (SocketChannel) selectionKey.channel();
        final ClientContext clientContext = (ClientContext) selectionKey.attachment();
        final ClientContextType type = clientContext.getType();

        try {
            if (type == ClientContextType.LOCAL) {
                final ClientLocalContext clientLocalContext = (ClientLocalContext) clientContext;
                final SocksStatus status = clientLocalContext.getStatus();

                switch (status) {
                    case SOCKS_AUTH:
                        processSocksAuthRead(socketChannel, clientLocalContext);
                        break;

                    case SOCKS_REQ:
                        processSocksReqRead(socketChannel, clientLocalContext);
                        break;

                    case SOCKS_PROXY:
                        processSocksProxyRead(socketChannel, clientLocalContext);
                        break;

                    default:
                        throw new HikariRuntimeException(String.format("client status '%s' not supported", status.name()));
                }

            }
            else if (type == ClientContextType.REMOTE) {
                final ClientRemoteContext clientRemoteContext = (ClientRemoteContext) clientContext;
                final HikariStatus status = clientRemoteContext.getStatus();

                switch (status) {
                    case HIKARI_AUTH:
                        processHikariAuthRead(socketChannel, clientRemoteContext);
                        break;

                    case HIKARI_PROXY:
                        processHikariProxyRead(socketChannel, clientRemoteContext);
                        break;

                    default:
                        throw new HikariRuntimeException(String.format("hikari status '%s' not supported", status.name()));
                }
            }
            else {
                throw new HikariRuntimeException(String.format("client context type '%s' not supported", type.name()));
            }
        } catch (Exception e) {
            logger.warn("handle read exception: {}", e.getMessage(), e);
            clientContext.close();
        }
    }

    private void processSocksAuthRead(final SocketChannel socketChannel, final ClientLocalContext clientLocalContext) throws IOException {
        dataBuffer.clear();
        if (!read(socketChannel, dataBuffer, clientLocalContext)) {
            return;
        }
        dataBuffer.flip();

        byte socksVer = dataBuffer.get();
        if (socksVer != SocksProtocol.VERSION_SOCKS5) {
            logger.warn("socks version '{}' not supported", socksVer);
            clientLocalContext.close();
            return;
        }

        // response
        packetBuffer.clear();
        packetBuffer.put(SocksProtocol.VERSION_SOCKS5);
        packetBuffer.put(SocksProtocol.AUTH_METHOD_NO_AUTH);
        packetBuffer.flip();
        socketChannel.write(packetBuffer);

        // set status
        clientLocalContext.setStatus(SocksStatus.SOCKS_REQ);
    }

    private void processSocksReqRead(final SocketChannel socketChannel, final ClientLocalContext clientLocalContext) throws IOException {
        dataBuffer.clear();
        if (!read(socketChannel, dataBuffer, clientLocalContext)) {
            return;
        }
        dataBuffer.flip();

        // ver
        dataBuffer.get();

        // command
        final byte command = dataBuffer.get();
        if (command != SocksProtocol.REQ_COMMAND_CONNECT) {
            writeSocksFail(SocksProtocol.REQ_REPLAY_COMMAND_NOT_SUPPORTED, socketChannel, clientLocalContext);
            return;
        }

        // rsv
        dataBuffer.get();

        // address
        final byte addressType = dataBuffer.get();
        final byte hikariAddressType;
        final byte[] address;

        if (addressType == SocksProtocol.ADDRESS_TYPE_DOMAIN) {
            int length = dataBuffer.get();
            byte[] tmpArray = new byte[length];
            dataBuffer.get(tmpArray, 0, length);

            if (clientConfig.getLocalDnsResolve()) {
                // local dns resolve
                String domainName = new String(tmpArray, StandardCharsets.US_ASCII);
                InetAddress inetAddress;
                try {
                    inetAddress = InetAddress.getByName(domainName);
                } catch (UnknownHostException e) {
                    logger.error("DNS resolve fail: {}", domainName);
                    writeSocksFail(SocksProtocol.REQ_REPLAY_HOST_UNREACHABLE, socketChannel, clientLocalContext);
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
                address = tmpArray;
            }
        }
        else if (addressType == SocksProtocol.ADDRESS_TYPE_IPV4) {
            address = new byte[4];
            dataBuffer.get(address, 0, 4);

            hikariAddressType = HikariProtocol.ADDRESS_TYPE_IPV4;
        }
        else if (addressType == SocksProtocol.ADDRESS_TYPE_IPV6) {
            address = new byte[16];
            dataBuffer.get(address, 0, 16);

            hikariAddressType = HikariProtocol.ADDRESS_TYPE_IPV6;
        }
        else {
            writeSocksFail(SocksProtocol.REQ_REPLAY_ADDRESS_TYPE_NOT_SUPPORTED, socketChannel, clientLocalContext);
            return;
        }

        // port
        final byte[] port = new byte[2];
        dataBuffer.get(port, 0, 2);

        // set context
        clientLocalContext.setHikariAddressType(hikariAddressType);
        clientLocalContext.setAddress(address);
        clientLocalContext.setPort(port);

        // connection to server
        final SocketAddress serverAddress = getServerAddress();
        final SelectionKey localKey = clientLocalContext.getSelectionKey();
        final Selector selector = localKey.selector();

        SocketChannel remoteChannel = SocketChannel.open();
        remoteChannel.configureBlocking(false);

        final SelectionKey remoteKey = remoteChannel.register(selector, SelectionKey.OP_CONNECT);

        PacketContext packetContext = new PacketContext(ByteBuffer.allocateDirect(packetBuffer.capacity()));
        ClientRemoteContext remoteContext = new ClientRemoteContext(remoteKey, packetContext, clientLocalContext, HikariStatus.HIKARI_AUTH);
        remoteKey.attach(remoteContext);

        clientLocalContext.setRemoteContext(remoteContext);

        boolean connectedNow = remoteChannel.connect(serverAddress);
        if (connectedNow) {
            handleConnect(remoteKey);
        }
    }

    private void processSocksProxyRead(final SocketChannel socketChannel, final ClientLocalContext clientLocalContext) throws IOException {
        dataBuffer.clear();
        if (!read(socketChannel, dataBuffer, clientLocalContext)) {
            return;
        }
        dataBuffer.flip();

        // encrypt
        cryptoBuffer.clear();
        encrypt(dataBuffer, cryptoBuffer, hikariCrypto);
        cryptoBuffer.flip();

        // wrap packet
        packetBuffer.clear();
        wrapPacket(cryptoBuffer, packetBuffer);
        packetBuffer.flip();

        // write
        final SocketChannel remoteChannel = (SocketChannel) clientLocalContext.getRemoteContext().getSelectionKey().channel();
        remoteChannel.write(packetBuffer);
    }

    private void processHikariAuthRead(final SocketChannel socketChannel, final ClientRemoteContext clientRemoteContext) throws IOException {
        packetBuffer.clear();
        if (!read(socketChannel, packetBuffer, clientRemoteContext)) {
            return;
        }
        packetBuffer.flip();

        // unwrap and decrypt


        final ClientLocalContext clientLocalContext = clientRemoteContext.getLocalContext();
        final SocketChannel localSocketChannel = (SocketChannel) clientLocalContext.getSelectionKey().channel();

        // ver
        dataBuffer.get();

        // reply
        final byte reply = dataBuffer.get();
        switch (reply) {
            case HikariProtocol.AUTH_RESPONSE_OK:
                // bind address type and address
                final byte bindHikariAddressType = dataBuffer.get();
                final byte socksAddressType;
                final byte[] bindAddress;

                if (bindHikariAddressType == HikariProtocol.ADDRESS_TYPE_IPV4) {
                    socksAddressType = SocksProtocol.ADDRESS_TYPE_IPV4;
                    bindAddress = new byte[4];
                    dataBuffer.get(bindAddress, 0, 4);
                }
                else if (bindHikariAddressType == HikariProtocol.ADDRESS_TYPE_IPV6) {
                    socksAddressType = SocksProtocol.ADDRESS_TYPE_IPV6;
                    bindAddress = new byte[16];
                    dataBuffer.get(bindAddress, 0, 16);
                }
                else {
                    logger.error("bad server response, hikari address type: {}", bindHikariAddressType);
                    writeSocksFail(SocksProtocol.REQ_REPLAY_GENERAL_FAILURE, localSocketChannel, clientRemoteContext);
                    break;
                }

                // bind port
                final byte[] port = new byte[2];
                dataBuffer.get(port, 0, 2);

                // response
                packetBuffer.clear();
                packetBuffer.put(SocksProtocol.VERSION_SOCKS5);
                packetBuffer.put(SocksProtocol.REQ_REPLAY_SUCCEEDED);
                packetBuffer.put((byte) 0x00);
                packetBuffer.put(socksAddressType);
                packetBuffer.put(bindAddress);
                packetBuffer.put(port);
                packetBuffer.flip();
                localSocketChannel.write(packetBuffer);

                // set status
                clientLocalContext.setStatus(SocksStatus.SOCKS_PROXY);
                clientRemoteContext.setStatus(HikariStatus.HIKARI_PROXY);
                break;

            case HikariProtocol.AUTH_RESPONSE_VERSION_NOT_SUPPORT:
                logger.error("server: hikari version not support");
                writeSocksFail(SocksProtocol.REQ_REPLAY_GENERAL_FAILURE, localSocketChannel, clientRemoteContext);
                break;

            case HikariProtocol.AUTH_RESPONSE_AUTH_FAIL:
                logger.error("server: auth fail");
                writeSocksFail(SocksProtocol.REQ_REPLAY_GENERAL_FAILURE, localSocketChannel, clientRemoteContext);
                break;

            case HikariProtocol.AUTH_RESPONSE_ENCRYPT_TYPE_NOT_SUPPORT:
                logger.error("server: encrypt type not support");
                writeSocksFail(SocksProtocol.REQ_REPLAY_GENERAL_FAILURE, localSocketChannel, clientRemoteContext);
                break;

            case HikariProtocol.AUTH_RESPONSE_DNS_RESOLVE_FAIL:
                byte[] address = clientLocalContext.getAddress();
                String domainName = new String(address, StandardCharsets.US_ASCII);
                logger.error("server: DNS resolve fail, domain name: {}", domainName);
                writeSocksFail(SocksProtocol.REQ_REPLAY_HOST_UNREACHABLE, localSocketChannel, clientRemoteContext);
                break;

            case HikariProtocol.AUTH_RESPONSE_CONNECT_TARGET_FAIL:
                logger.error("server: connect to target fail");
                writeSocksFail(SocksProtocol.REQ_REPLAY_NETWORK_UNREACHABLE, localSocketChannel, clientRemoteContext);
                break;

            default:
                logger.error("bad server response: {}", reply);
                writeSocksFail(SocksProtocol.REQ_REPLAY_GENERAL_FAILURE, localSocketChannel, clientRemoteContext);
        }
    }

    private void processHikariProxyRead(final SocketChannel socketChannel, final ClientRemoteContext clientRemoteContext) throws IOException {
        if (!readToBuffer(socketChannel, clientRemoteContext)) {
            return;
        }

        // unwrap packet
        final SocketChannel localChannel = (SocketChannel) clientRemoteContext.getLocalContext().getSelectionKey().channel();
        final PacketContext packetContext = clientRemoteContext.getPacketContext();

        try {
            while (unwrapPacket(packetContext)) {
                decrypt(hikariCrypto);
                localChannel.write(packetBuffer);
            }
        } catch (HikariChecksumFailException e) {
            logger.warn("server packet checksum fail");
            clientRemoteContext.close();
        }
    }

    private SocketAddress getServerAddress() {
        SocketAddress serverAddress = serverAddressArray[currentAddressIndex++];
        if (currentAddressIndex > maxAddressIndex) {
            currentAddressIndex = 0;
        }

        return serverAddress;
    }

    private void writeSocksFail(final byte response, final SocketChannel socketChannel, final ClientContext clientContext) throws IOException {
        packetBuffer.clear();
        packetBuffer.put(SocksProtocol.VERSION_SOCKS5);
        packetBuffer.put(response);
        packetBuffer.flip();

        socketChannel.write(packetBuffer);
        clientContext.close();
    }
}
