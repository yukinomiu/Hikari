package com.github.yukinomiu.hikari.client;

import com.github.yukinomiu.hikari.common.*;
import com.github.yukinomiu.hikari.common.exception.HikariRuntimeException;
import com.github.yukinomiu.hikari.common.protocol.HikariProtocol;
import com.github.yukinomiu.hikari.common.protocol.SocksProtocol;
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
import java.util.List;

/**
 * Yukinomiu
 * 2018/1/22
 */
public class ClientHandler implements HikariHandle {
    private static final Logger logger = LoggerFactory.getLogger(ClientHandler.class);

    private final ClientConfig clientConfig;
    private final ByteBuffer readBuffer;
    private final ByteBuffer writeBuffer;

    private final SocketAddress[] serverAddressArray;
    private final int maxAddressIndex;
    private int currentAddressIndex;

    private final byte[] privateKeyHash;

    public ClientHandler(ClientConfig clientConfig) {
        this.clientConfig = clientConfig;

        // init buffer
        final int bufferSize = HikariConstant.BUFFER_SIZE;
        readBuffer = ByteBuffer.allocateDirect(bufferSize);
        writeBuffer = ByteBuffer.allocateDirect(bufferSize + 2);

        // init server address
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

        // init private key
        String privateKey = clientConfig.getPrivateKey();
        privateKeyHash = DigestUtils.md5(privateKey);
    }

    @Override
    public void handleAccept(final SelectionKey selectionKey) {
        final Selector selector = selectionKey.selector();
        final ServerSocketChannel serverSocketChannel = (ServerSocketChannel) selectionKey.channel();

        try {
            SocketChannel socketChannel = serverSocketChannel.accept();
            socketChannel.configureBlocking(false);

            SelectionKey localKey = socketChannel.register(selector, SelectionKey.OP_READ);
            final ClientLocalContext clientLocalContext = new ClientLocalContext(localKey, SocksStatus.SOCKS_AUTH);
            localKey.attach(clientLocalContext);
        } catch (Exception e) {
            String msg = e.getMessage();
            logger.warn("handle accept exception: {}", msg != null ? msg : e.getClass().getName());
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
                writeSocksReqFailResponse(SocksProtocol.REQ_REPLAY_HOST_UNREACHABLE, localChannel, clientRemoteContext);
                return;
            }

            selectionKey.interestOps(SelectionKey.OP_READ);

            // send hikari request
            final byte hikariAddressType = clientLocalContext.getHikariAddressType();
            final byte[] address = clientLocalContext.getAddress();
            final byte[] port = clientLocalContext.getPort();

            writeBuffer.clear();
            writeBuffer.put(HikariProtocol.VERSION_HIKARI1);
            writeBuffer.put(privateKeyHash);
            writeBuffer.put(HikariProtocol.ENCRYPT_PLAIN);
            writeBuffer.put(hikariAddressType);
            if (hikariAddressType == HikariProtocol.ADDRESS_TYPE_DOMAIN) {
                writeBuffer.put((byte) address.length);
            }
            writeBuffer.put(address);
            writeBuffer.put(port);

            writeBuffer.flip();
            remoteChannel.write(writeBuffer);
        } catch (Exception e) {
            String msg = e.getMessage();
            logger.warn("handle connect exception: {}", msg != null ? msg : e.getClass().getName());

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
            String msg = e.getMessage();
            logger.warn("handle read exception: {}", msg != null ? msg : e.getClass().getName());

            clientContext.close();
        }
    }

    private void processSocksAuthRead(final SocketChannel socketChannel, final ClientLocalContext clientLocalContext) throws IOException {
        if (!readToBuffer(socketChannel, clientLocalContext, readBuffer)) {
            return;
        }

        byte socksVer = readBuffer.get();
        if (socksVer != SocksProtocol.VERSION_SOCKS5) {
            logger.warn("socks version '{}' not supported", socksVer);
            clientLocalContext.close();
            return;
        }

        // response
        writeBuffer.clear();
        writeBuffer.put(SocksProtocol.VERSION_SOCKS5);
        writeBuffer.put(SocksProtocol.AUTH_METHOD_NO_AUTH);
        writeBuffer.flip();
        socketChannel.write(writeBuffer);

        // set status
        clientLocalContext.setStatus(SocksStatus.SOCKS_REQ);
    }

    private void processSocksReqRead(final SocketChannel socketChannel, final ClientLocalContext clientLocalContext) throws IOException {
        if (!readToBuffer(socketChannel, clientLocalContext, readBuffer)) {
            return;
        }

        // ver
        readBuffer.get();

        // command
        final byte command = readBuffer.get();
        if (command != SocksProtocol.REQ_COMMAND_CONNECT) {
            writeSocksReqFailResponse(SocksProtocol.REQ_REPLAY_COMMAND_NOT_SUPPORTED, socketChannel, clientLocalContext);
            return;
        }

        // rsv
        readBuffer.get();

        // address
        final byte addressType = readBuffer.get();
        final byte hikariAddressType;
        final byte[] address;

        if (addressType == SocksProtocol.ADDRESS_TYPE_DOMAIN) {
            int length = readBuffer.get();
            byte[] tmpArray = new byte[length];
            readBuffer.get(tmpArray, 0, length);

            if (clientConfig.getLocalDnsResolve()) {
                // local dns resolve
                String domainName = new String(tmpArray, StandardCharsets.US_ASCII);
                InetAddress inetAddress;
                try {
                    inetAddress = InetAddress.getByName(domainName);
                } catch (UnknownHostException e) {
                    logger.error("DNS resolve fail: {}", domainName);
                    writeSocksReqFailResponse(SocksProtocol.REQ_REPLAY_HOST_UNREACHABLE, socketChannel, clientLocalContext);
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
            readBuffer.get(address, 0, 4);

            hikariAddressType = HikariProtocol.ADDRESS_TYPE_IPV4;
        }
        else if (addressType == SocksProtocol.ADDRESS_TYPE_IPV6) {
            address = new byte[16];
            readBuffer.get(address, 0, 16);

            hikariAddressType = HikariProtocol.ADDRESS_TYPE_IPV6;
        }
        else {
            writeSocksReqFailResponse(SocksProtocol.REQ_REPLAY_ADDRESS_TYPE_NOT_SUPPORTED, socketChannel, clientLocalContext);
            return;
        }

        // port
        final byte[] port = new byte[2];
        readBuffer.get(port, 0, 2);

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

        PacketContext packetContext = new PacketContext(ByteBuffer.allocateDirect(writeBuffer.capacity()));
        ClientRemoteContext remoteContext = new ClientRemoteContext(remoteKey, packetContext, clientLocalContext, HikariStatus.HIKARI_AUTH);
        remoteKey.attach(remoteContext);

        clientLocalContext.setRemoteContext(remoteContext);

        boolean connectedNow = remoteChannel.connect(serverAddress);
        if (connectedNow) {
            handleConnect(remoteKey);
        }
    }

    private void processSocksProxyRead(final SocketChannel socketChannel, final ClientLocalContext clientLocalContext) throws IOException {
        if (!readToBuffer(socketChannel, clientLocalContext, readBuffer)) {
            return;
        }

        wrapPacket(readBuffer, writeBuffer);

        final SocketChannel remoteChannel = (SocketChannel) clientLocalContext.getRemoteContext().getSelectionKey().channel();
        remoteChannel.write(writeBuffer);
    }

    private void processHikariAuthRead(final SocketChannel socketChannel, final ClientRemoteContext clientRemoteContext) throws IOException {
        if (!readToBuffer(socketChannel, clientRemoteContext, readBuffer)) {
            return;
        }

        final ClientLocalContext clientLocalContext = clientRemoteContext.getLocalContext();
        final SocketChannel localSocketChannel = (SocketChannel) clientLocalContext.getSelectionKey().channel();

        // ver
        readBuffer.get();

        // reply
        final byte reply = readBuffer.get();
        switch (reply) {
            case HikariProtocol.AUTH_RESPONSE_OK:
                // bind address type and address
                final byte bindHikariAddressType = readBuffer.get();
                final byte socksAddressType;
                final byte[] bindAddress;

                if (bindHikariAddressType == HikariProtocol.ADDRESS_TYPE_IPV4) {
                    socksAddressType = SocksProtocol.ADDRESS_TYPE_IPV4;
                    bindAddress = new byte[4];
                    readBuffer.get(bindAddress, 0, 4);
                }
                else if (bindHikariAddressType == HikariProtocol.ADDRESS_TYPE_IPV6) {
                    socksAddressType = SocksProtocol.ADDRESS_TYPE_IPV6;
                    bindAddress = new byte[16];
                    readBuffer.get(bindAddress, 0, 16);
                }
                else {
                    logger.error("bad server response, hikari address type: {}", bindHikariAddressType);
                    writeSocksReqFailResponse(SocksProtocol.REQ_REPLAY_GENERAL_FAILURE, localSocketChannel, clientRemoteContext);
                    break;
                }

                // bind port
                final byte[] port = new byte[2];
                readBuffer.get(port, 0, 2);

                // response
                writeBuffer.clear();
                writeBuffer.put(SocksProtocol.VERSION_SOCKS5);
                writeBuffer.put(SocksProtocol.REQ_REPLAY_SUCCEEDED);
                writeBuffer.put((byte) 0x00);
                writeBuffer.put(socksAddressType);
                writeBuffer.put(bindAddress);
                writeBuffer.put(port);
                writeBuffer.flip();
                localSocketChannel.write(writeBuffer);

                // set status
                clientLocalContext.setStatus(SocksStatus.SOCKS_PROXY);
                clientRemoteContext.setStatus(HikariStatus.HIKARI_PROXY);
                break;

            case HikariProtocol.AUTH_RESPONSE_VERSION_NOT_SUPPORT:
                logger.error("server: hikari version not support");
                writeSocksReqFailResponse(SocksProtocol.REQ_REPLAY_GENERAL_FAILURE, localSocketChannel, clientRemoteContext);
                break;

            case HikariProtocol.AUTH_RESPONSE_AUTH_FAIL:
                logger.error("server: auth fail");
                writeSocksReqFailResponse(SocksProtocol.REQ_REPLAY_GENERAL_FAILURE, localSocketChannel, clientRemoteContext);
                break;

            case HikariProtocol.AUTH_RESPONSE_ENCRYPT_TYPE_NOT_SUPPORT:
                logger.error("server: encrypt type not support");
                writeSocksReqFailResponse(SocksProtocol.REQ_REPLAY_GENERAL_FAILURE, localSocketChannel, clientRemoteContext);
                break;

            case HikariProtocol.AUTH_RESPONSE_DNS_RESOLVE_FAIL:
                byte[] address = clientLocalContext.getAddress();
                String domainName = new String(address, StandardCharsets.US_ASCII);
                logger.error("server: DNS resolve fail, domain name: {}", domainName);
                writeSocksReqFailResponse(SocksProtocol.REQ_REPLAY_HOST_UNREACHABLE, localSocketChannel, clientRemoteContext);
                break;

            case HikariProtocol.AUTH_RESPONSE_CONNECT_TARGET_FAIL:
                logger.error("server: connect to target fail");
                writeSocksReqFailResponse(SocksProtocol.REQ_REPLAY_NETWORK_UNREACHABLE, localSocketChannel, clientRemoteContext);
                break;

            default:
                logger.error("bad server response: {}", reply);
                writeSocksReqFailResponse(SocksProtocol.REQ_REPLAY_GENERAL_FAILURE, localSocketChannel, clientRemoteContext);
        }
    }

    private void processHikariProxyRead(final SocketChannel socketChannel, final ClientRemoteContext clientRemoteContext) throws IOException {
        if (!readToBuffer(socketChannel, clientRemoteContext, readBuffer)) {
            return;
        }

        // unwrap packet
        final SocketChannel localChannel = (SocketChannel) clientRemoteContext.getLocalContext().getSelectionKey().channel();
        final PacketContext packetContext = clientRemoteContext.getPacketContext();

        while (unwrapPacket(readBuffer, writeBuffer, packetContext)) {
            localChannel.write(writeBuffer);
        }
    }

    private SocketAddress getServerAddress() {
        SocketAddress serverAddress = serverAddressArray[currentAddressIndex++];
        if (currentAddressIndex > maxAddressIndex) {
            currentAddressIndex = 0;
        }

        return serverAddress;
    }

    private void writeSocksReqFailResponse(final byte response, final SocketChannel socketChannel, final ClientContext clientContext) throws IOException {
        writeBuffer.clear();
        writeBuffer.put(SocksProtocol.VERSION_SOCKS5);
        writeBuffer.put(response);
        writeBuffer.flip();

        socketChannel.write(writeBuffer);
        clientContext.close();
    }
}
