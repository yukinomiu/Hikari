package com.github.yukinomiu.hikari.client;

import com.github.yukinomiu.hikari.common.*;
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

    private final SocketAddress[] serverAddressArray;
    private final int maxAddressIndex;
    private int currentAddressIndex;

    private final byte[] privateKeyHash;

    public ClientHandler(final ClientConfig clientConfig) {
        super(clientConfig);

        // config
        this.clientConfig = clientConfig;

        // server address
        String serverAddress = clientConfig.getServerAddress();
        List<Integer> serverPortList = clientConfig.getServerPortList();
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
            dataBuffer.put(hikariAddressType);
            if (hikariAddressType == HikariProtocol.ADDRESS_TYPE_DOMAIN) {
                dataBuffer.put((byte) address.length);
            }
            dataBuffer.put(address);
            dataBuffer.put(port);
            dataBuffer.flip();

            // write
            encryptWrite(dataBuffer, remoteChannel);
        } catch (Exception e) {
            String msg = e.getMessage();
            logger.warn("handle connect exception: {}", msg != null ? msg : e.getClass().getName());
            clientRemoteContext.close();
        }
    }

    @Override
    public void consumeData(final SelectionKey selectionKey,
                            final SocketChannel socketChannel,
                            final ByteBuffer data,
                            final HikariContext hikariContext) throws Exception {
        final ClientContext clientContext = (ClientContext) hikariContext;
        final ClientContextType type = clientContext.getType();

        if (type == ClientContextType.LOCAL) {
            final ClientLocalContext clientLocalContext = (ClientLocalContext) clientContext;
            final SocksStatus status = clientLocalContext.getStatus();

            switch (status) {
                case SOCKS_AUTH:
                    processSocksAuthRead(socketChannel, data, clientLocalContext);
                    break;

                case SOCKS_REQ:
                    processSocksReqRead(socketChannel, data, clientLocalContext);
                    break;

                case SOCKS_PROXY:
                    processSocksProxyRead(socketChannel, data, clientLocalContext);
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
                    processHikariAuthRead(socketChannel, data, clientRemoteContext);
                    break;

                case HIKARI_PROXY:
                    processHikariProxyRead(socketChannel, data, clientRemoteContext);
                    break;

                default:
                    throw new HikariRuntimeException(String.format("hikari status '%s' not supported", status.name()));
            }
        }
        else {
            throw new HikariRuntimeException(String.format("client context type '%s' not supported", type.name()));
        }
    }

    private void processSocksAuthRead(final SocketChannel socketChannel, final ByteBuffer data, final ClientLocalContext clientLocalContext) throws IOException {
        final byte ver = data.get();
        if (ver != SocksProtocol.VERSION_SOCKS5) {
            logger.warn("socks version '{}' not supported", ver);
            clientLocalContext.close();
            return;
        }

        // response
        data.clear();
        data.put(SocksProtocol.VERSION_SOCKS5);
        data.put(SocksProtocol.AUTH_METHOD_NO_AUTH);
        data.flip();
        socketChannel.write(data);

        // set status
        clientLocalContext.setStatus(SocksStatus.SOCKS_REQ);
    }

    private void processSocksReqRead(final SocketChannel socketChannel, final ByteBuffer data, final ClientLocalContext clientLocalContext) throws IOException {
        // ver
        data.get();

        // command
        final byte command = data.get();
        if (command != SocksProtocol.REQ_COMMAND_CONNECT) {
            writeSocksFail(SocksProtocol.REQ_REPLAY_COMMAND_NOT_SUPPORTED, socketChannel, clientLocalContext);
            return;
        }

        // rsv
        data.get();

        // address
        final byte addressType = data.get();
        final byte hikariAddressType;
        final byte[] address;

        if (addressType == SocksProtocol.ADDRESS_TYPE_DOMAIN) {
            int length = data.get();
            byte[] tmpArray = new byte[length];
            data.get(tmpArray, 0, length);

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
            data.get(address, 0, 4);

            hikariAddressType = HikariProtocol.ADDRESS_TYPE_IPV4;
        }
        else if (addressType == SocksProtocol.ADDRESS_TYPE_IPV6) {
            address = new byte[16];
            data.get(address, 0, 16);

            hikariAddressType = HikariProtocol.ADDRESS_TYPE_IPV6;
        }
        else {
            writeSocksFail(SocksProtocol.REQ_REPLAY_ADDRESS_TYPE_NOT_SUPPORTED, socketChannel, clientLocalContext);
            return;
        }

        // port
        final byte[] port = new byte[2];
        data.get(port, 0, 2);

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

        PacketContext packetContext = getNewPacketContext();
        ClientRemoteContext remoteContext = new ClientRemoteContext(remoteKey, packetContext, clientLocalContext, HikariStatus.HIKARI_AUTH);
        remoteKey.attach(remoteContext);

        clientLocalContext.setRemoteContext(remoteContext);

        boolean connectedNow = remoteChannel.connect(serverAddress);
        if (connectedNow) {
            handleConnect(remoteKey);
        }
    }

    private void processSocksProxyRead(final SocketChannel socketChannel, final ByteBuffer data, final ClientLocalContext clientLocalContext) throws IOException {
        // write
        final SocketChannel remoteChannel = (SocketChannel) clientLocalContext.getRemoteContext().getSelectionKey().channel();
        encryptWrite(data, remoteChannel);
    }

    private void processHikariAuthRead(final SocketChannel socketChannel, final ByteBuffer data, final ClientRemoteContext clientRemoteContext) throws IOException {
        final ClientLocalContext clientLocalContext = clientRemoteContext.getLocalContext();
        final SocketChannel localSocketChannel = (SocketChannel) clientLocalContext.getSelectionKey().channel();

        // ver
        data.get();

        // reply
        final byte reply = data.get();
        switch (reply) {
            case HikariProtocol.AUTH_RESPONSE_OK:
                // bind address type and address
                final byte bindHikariAddressType = data.get();
                final byte socksAddressType;
                final byte[] bindAddress;

                if (bindHikariAddressType == HikariProtocol.ADDRESS_TYPE_IPV4) {
                    socksAddressType = SocksProtocol.ADDRESS_TYPE_IPV4;
                    bindAddress = new byte[4];
                    data.get(bindAddress, 0, 4);
                }
                else if (bindHikariAddressType == HikariProtocol.ADDRESS_TYPE_IPV6) {
                    socksAddressType = SocksProtocol.ADDRESS_TYPE_IPV6;
                    bindAddress = new byte[16];
                    data.get(bindAddress, 0, 16);
                }
                else {
                    logger.error("bad server response, hikari address type: {}", bindHikariAddressType);
                    writeSocksFail(SocksProtocol.REQ_REPLAY_GENERAL_FAILURE, localSocketChannel, clientRemoteContext);
                    break;
                }

                // bind port
                final byte[] port = new byte[2];
                data.get(port, 0, 2);

                // response
                data.clear();
                data.put(SocksProtocol.VERSION_SOCKS5);
                data.put(SocksProtocol.REQ_REPLAY_SUCCEEDED);
                data.put((byte) 0x00);
                data.put(socksAddressType);
                data.put(bindAddress);
                data.put(port);
                data.flip();
                localSocketChannel.write(data);

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

    private void processHikariProxyRead(final SocketChannel socketChannel, final ByteBuffer data, final ClientRemoteContext clientRemoteContext) throws IOException {
        final SocketChannel localChannel = (SocketChannel) clientRemoteContext.getLocalContext().getSelectionKey().channel();
        localChannel.write(data);
    }

    private SocketAddress getServerAddress() {
        SocketAddress serverAddress = serverAddressArray[currentAddressIndex++];
        if (currentAddressIndex > maxAddressIndex) {
            currentAddressIndex = 0;
        }

        return serverAddress;
    }

    private void writeSocksFail(final byte response, final SocketChannel socketChannel, final ClientContext clientContext) throws IOException {
        dataBuffer.clear();
        dataBuffer.put(SocksProtocol.VERSION_SOCKS5);
        dataBuffer.put(response);
        dataBuffer.flip();

        socketChannel.write(dataBuffer);
        clientContext.close();
    }
}
