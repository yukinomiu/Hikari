package com.github.yukinomiu.hikari.client;

import com.github.yukinomiu.hikari.common.HikariHandle;
import com.github.yukinomiu.hikari.common.HikariStatus;
import com.github.yukinomiu.hikari.common.SocksStatus;
import com.github.yukinomiu.hikari.common.constants.HikariConstants;
import com.github.yukinomiu.hikari.common.constants.SocksConstants;
import com.github.yukinomiu.hikari.common.exception.HikariRuntimeException;
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
    private final ByteBuffer buffer;

    private final SocketAddress[] serverAddressArray;
    private final int maxAddressIndex;
    private int currentAddressIndex;

    private final byte[] privateKeyHash;

    public ClientHandler(ClientConfig clientConfig) {
        this.clientConfig = clientConfig;

        // init buffer
        Integer bufferSize = clientConfig.getBufferSize();
        buffer = ByteBuffer.allocateDirect(bufferSize);

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

            final ClientLocalContext clientLocalContext = new ClientLocalContext();
            SelectionKey localKey = socketChannel.register(selector, SelectionKey.OP_READ, clientLocalContext);

            clientLocalContext.setSelectionKey(localKey);
            clientLocalContext.setStatus(SocksStatus.SOCKS_AUTH);
        } catch (Exception e) {
            logger.warn("handle accept exception: {}", e);
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
                writeSocksReqFailResponse(buffer, SocksConstants.REQ_REPLAY_HOST_UNREACHABLE, localChannel, clientRemoteContext);
                return;
            }

            selectionKey.interestOps(SelectionKey.OP_READ);

            // send hikari request
            final byte hikariAddressType = clientLocalContext.getHikariAddressType();
            final byte[] address = clientLocalContext.getAddress();
            final byte[] port = clientLocalContext.getPort();

            buffer.put(HikariConstants.VERSION_HIKARI1);
            buffer.put(privateKeyHash);
            buffer.put(HikariConstants.ENCRYPT_PLAIN);
            buffer.put(hikariAddressType);
            if (hikariAddressType == HikariConstants.ADDRESS_TYPE_DOMAIN) {
                buffer.put((byte) address.length);
            }
            buffer.put(address);
            buffer.put(port);

            buffer.flip();
            remoteChannel.write(buffer);
        } catch (Exception e) {
            logger.warn("handle connect exception: {}", e);
            clientRemoteContext.close();
        } finally {
            buffer.clear();
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
            logger.warn("handle read exception: {}", e);
            clientContext.close();
        } finally {
            buffer.clear();
        }
    }

    private void processSocksAuthRead(final SocketChannel socketChannel, final ClientLocalContext clientLocalContext) throws IOException {
        socketChannel.read(buffer);
        buffer.flip();

        byte socksVer = buffer.get();
        if (socksVer != SocksConstants.VERSION_SOCKS5) {
            logger.warn("socks version '{}' not supported", socksVer);
            clientLocalContext.close();
            return;
        }

        // response
        buffer.clear();
        buffer.put(SocksConstants.VERSION_SOCKS5);
        buffer.put(SocksConstants.AUTH_METHOD_NO_AUTH);
        buffer.flip();
        socketChannel.write(buffer);

        // set status
        clientLocalContext.setStatus(SocksStatus.SOCKS_REQ);
    }

    private void processSocksReqRead(final SocketChannel socketChannel, final ClientLocalContext clientLocalContext) throws IOException {
        socketChannel.read(buffer);
        buffer.flip();

        // ver
        buffer.get();

        final byte command = buffer.get(); // command
        if (command != SocksConstants.REQ_COMMAND_CONNECT) {
            writeSocksReqFailResponse(buffer, SocksConstants.REQ_REPLAY_COMMAND_NOT_SUPPORTED, socketChannel, clientLocalContext);
            return;
        }

        // rsv
        buffer.get();

        // address
        final byte addressType = buffer.get();
        final byte hikariAddressType;
        final byte[] address;

        if (addressType == SocksConstants.ADDRESS_TYPE_DOMAIN) {
            int length = buffer.get();
            byte[] tmpArray = new byte[length];
            buffer.get(tmpArray, 0, length);

            if (clientConfig.getLocalDnsResolve()) {
                // local dns resolve
                String domainName = new String(tmpArray, StandardCharsets.US_ASCII);
                InetAddress inetAddress;
                try {
                    inetAddress = InetAddress.getByName(domainName);
                } catch (UnknownHostException e) {
                    logger.error("DNS resolve fail: {}", domainName);
                    writeSocksReqFailResponse(buffer, SocksConstants.REQ_REPLAY_HOST_UNREACHABLE, socketChannel, clientLocalContext);
                    return;
                }

                if (inetAddress instanceof Inet4Address) {
                    hikariAddressType = HikariConstants.ADDRESS_TYPE_IPV4;
                    address = inetAddress.getAddress();
                }
                else if (inetAddress instanceof Inet6Address) {
                    hikariAddressType = HikariConstants.ADDRESS_TYPE_IPV6;
                    address = inetAddress.getAddress();
                }
                else {
                    throw new HikariRuntimeException(String.format("address type '%s' not supported", inetAddress.toString()));
                }
            }
            else {
                hikariAddressType = HikariConstants.ADDRESS_TYPE_DOMAIN;
                address = tmpArray;
            }
        }
        else if (addressType == SocksConstants.ADDRESS_TYPE_IPV4) {
            address = new byte[4];
            buffer.get(address, 0, 4);

            hikariAddressType = HikariConstants.ADDRESS_TYPE_IPV4;
        }
        else if (addressType == SocksConstants.ADDRESS_TYPE_IPV6) {
            address = new byte[16];
            buffer.get(address, 0, 16);

            hikariAddressType = HikariConstants.ADDRESS_TYPE_IPV6;
        }
        else {
            writeSocksReqFailResponse(buffer, SocksConstants.REQ_REPLAY_ADDRESS_TYPE_NOT_SUPPORTED, socketChannel, clientLocalContext);
            return;
        }

        // port
        final byte[] port = new byte[2];
        buffer.get(port, 0, 2);

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

        ClientRemoteContext remoteContext = new ClientRemoteContext();
        final SelectionKey remoteKey = remoteChannel.register(selector, SelectionKey.OP_CONNECT, remoteContext);

        remoteContext.setSelectionKey(remoteKey);
        remoteContext.setStatus(HikariStatus.HIKARI_AUTH);
        remoteContext.setLocalContext(clientLocalContext);

        clientLocalContext.setRemoteContext(remoteContext);

        boolean connectedNow = remoteChannel.connect(serverAddress);
        if (connectedNow) {
            buffer.clear();
            handleConnect(remoteKey);
        }
    }

    private void processSocksProxyRead(final SocketChannel socketChannel, final ClientLocalContext clientLocalContext) throws IOException {
        int read = socketChannel.read(buffer);
        if (read == -1) {
            clientLocalContext.close();
            return;
        }
        else if (read == 0) {
            return;
        }

        final SocketChannel remoteChannel = (SocketChannel) clientLocalContext.getRemoteContext().getSelectionKey().channel();
        buffer.flip();
        remoteChannel.write(buffer);
    }

    private void processHikariAuthRead(final SocketChannel socketChannel, final ClientRemoteContext clientRemoteContext) throws IOException {
        socketChannel.read(buffer);
        buffer.flip();

        final ClientLocalContext clientLocalContext = clientRemoteContext.getLocalContext();
        final SocketChannel localSocketChannel = (SocketChannel) clientLocalContext.getSelectionKey().channel();

        // ver
        buffer.get();

        // reply
        final byte reply = buffer.get();
        switch (reply) {
            case HikariConstants.AUTH_RESPONSE_OK:
                // bind address type and address
                final byte bindHikariAddressType = buffer.get();
                final byte socksAddressType;
                final byte[] bindAddress;

                if (bindHikariAddressType == HikariConstants.ADDRESS_TYPE_IPV4) {
                    socksAddressType = SocksConstants.ADDRESS_TYPE_IPV4;
                    bindAddress = new byte[4];
                    buffer.get(bindAddress, 0, 4);
                }
                else if (bindHikariAddressType == HikariConstants.ADDRESS_TYPE_IPV6) {
                    socksAddressType = SocksConstants.ADDRESS_TYPE_IPV6;
                    bindAddress = new byte[16];
                    buffer.get(bindAddress, 0, 16);
                }
                else {
                    logger.error("bad server response, hikari address type: {}", bindHikariAddressType);
                    writeSocksReqFailResponse(buffer, SocksConstants.REQ_REPLAY_GENERAL_FAILURE, localSocketChannel, clientRemoteContext);
                    break;
                }

                // bind port
                final byte[] port = new byte[2];
                buffer.get(port, 0, 2);

                // response
                buffer.clear();
                buffer.put(SocksConstants.VERSION_SOCKS5);
                buffer.put(SocksConstants.REQ_REPLAY_SUCCEEDED);
                buffer.put((byte) 0x00);
                buffer.put(socksAddressType);
                buffer.put(bindAddress);
                buffer.put(port);
                buffer.flip();
                localSocketChannel.write(buffer);

                // set status
                clientLocalContext.setStatus(SocksStatus.SOCKS_PROXY);
                clientRemoteContext.setStatus(HikariStatus.HIKARI_PROXY);
                break;

            case HikariConstants.AUTH_RESPONSE_VERSION_NOT_SUPPORT:
                logger.error("server: hikari version not support");
                writeSocksReqFailResponse(buffer, SocksConstants.REQ_REPLAY_GENERAL_FAILURE, localSocketChannel, clientRemoteContext);
                break;

            case HikariConstants.AUTH_RESPONSE_AUTH_FAIL:
                logger.error("server: auth fail");
                writeSocksReqFailResponse(buffer, SocksConstants.REQ_REPLAY_GENERAL_FAILURE, localSocketChannel, clientRemoteContext);
                break;

            case HikariConstants.AUTH_RESPONSE_ENCRYPT_TYPE_NOT_SUPPORT:
                logger.error("server: encrypt type not support");
                writeSocksReqFailResponse(buffer, SocksConstants.REQ_REPLAY_GENERAL_FAILURE, localSocketChannel, clientRemoteContext);
                break;

            case HikariConstants.AUTH_RESPONSE_DNS_RESOLVE_FAIL:
                byte[] address = clientLocalContext.getAddress();
                String domainName = new String(address, StandardCharsets.US_ASCII);
                logger.error("server: DNS resolve fail, domain name: {}", domainName);
                writeSocksReqFailResponse(buffer, SocksConstants.REQ_REPLAY_HOST_UNREACHABLE, localSocketChannel, clientRemoteContext);
                break;

            case HikariConstants.AUTH_RESPONSE_CONNECT_TARGET_FAIL:
                logger.error("server: connect to target fail");
                writeSocksReqFailResponse(buffer, SocksConstants.REQ_REPLAY_NETWORK_UNREACHABLE, localSocketChannel, clientRemoteContext);
                break;

            default:
                logger.error("bad server response: {}", reply);
                writeSocksReqFailResponse(buffer, SocksConstants.REQ_REPLAY_GENERAL_FAILURE, localSocketChannel, clientRemoteContext);
        }
    }

    private void processHikariProxyRead(final SocketChannel socketChannel, final ClientRemoteContext clientRemoteContext) throws IOException {
        int read = socketChannel.read(buffer);
        if (read == -1) {
            clientRemoteContext.close();
            return;
        }
        else if (read == 0) {
            return;
        }

        final SocketChannel localChannel = (SocketChannel) clientRemoteContext.getLocalContext().getSelectionKey().channel();
        buffer.flip();
        localChannel.write(buffer);
    }

    private SocketAddress getServerAddress() {
        SocketAddress serverAddress = serverAddressArray[currentAddressIndex++];
        if (currentAddressIndex > maxAddressIndex) {
            currentAddressIndex = 0;
        }

        return serverAddress;
    }

    private void writeSocksReqFailResponse(final ByteBuffer buffer, final byte response, final SocketChannel socketChannel, final ClientContext clientContext) throws IOException {
        buffer.clear();
        buffer.put(SocksConstants.VERSION_SOCKS5);
        buffer.put(response);
        buffer.flip();

        socketChannel.write(buffer);
        clientContext.close();
    }
}
