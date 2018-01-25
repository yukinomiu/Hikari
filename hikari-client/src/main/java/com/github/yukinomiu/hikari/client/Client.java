package com.github.yukinomiu.hikari.client;

import com.github.yukinomiu.hikari.common.LifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.Iterator;
import java.util.Set;

/**
 * Yukinomiu
 * 2018/1/22
 */
public class Client implements LifeCycle {
    private static final Logger logger = LoggerFactory.getLogger(Client.class);
    private final ClientConfig clientConfig;

    private SelectionKey mainKey;
    private Thread clientThread;

    public Client(final ClientConfig clientConfig) {
        this.clientConfig = clientConfig;
    }

    @Override
    public void start() throws IOException {
        init();
        run();
    }

    @Override
    public void shutdown() throws IOException {
        stop();
        destroy();
    }

    private void init() throws IOException {
        logger.info("init resource");

        // open selector and channel
        Selector selector = Selector.open();
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.configureBlocking(false);

        // bind address
        String listenAddress = clientConfig.getListenAddress();
        Integer listenPort = clientConfig.getListenPort();
        SocketAddress socketAddress = new InetSocketAddress(listenAddress, listenPort);
        serverSocketChannel.bind(socketAddress);
        logger.info("listen on {}:{}", listenAddress, listenPort);

        // register
        mainKey = serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        // init thread
        clientThread = new Thread(new ClientMainLoop(mainKey));
    }

    private void destroy() throws IOException {
        logger.info("destroy resource");

        mainKey.cancel();
        mainKey.selector().close();
        mainKey.channel().close();
    }

    private void run() {
        clientThread.start();
    }

    private void stop() {
        clientThread.interrupt();
    }

    private class ClientMainLoop implements Runnable {
        private final SelectionKey selectionKey;

        private ClientMainLoop(final SelectionKey selectionKey) {
            this.selectionKey = selectionKey;
        }

        @Override
        public void run() {
            logger.info("client thread run");

            final ClientHandler clientHandler = new ClientHandler(clientConfig);
            final Selector selector = selectionKey.selector();

            // loop
            while (true) {
                try {
                    int count = selector.select();

                    if (Thread.currentThread().isInterrupted()) {
                        logger.info("client thread exit loop");
                        break;
                    }

                    if (count == 0) {
                        continue;
                    }

                    Set<SelectionKey> keys = selector.selectedKeys();
                    Iterator<SelectionKey> iterator = keys.iterator();
                    while (iterator.hasNext()) {
                        SelectionKey key = iterator.next();
                        iterator.remove();

                        if (!key.isValid()) {
                            key.cancel();
                            continue;
                        }

                        if (key.isReadable()) {
                            clientHandler.handleRead(key);
                        }
                        else if (key.isAcceptable()) {
                            clientHandler.handleAccept(key);
                        }
                        else if (key.isConnectable()) {
                            clientHandler.handleConnect(key);
                        }
                    }
                } catch (Exception e) {
                    logger.error("client main loop exception", e);
                }
            }

            logger.info("client thread stop");
        }
    }
}
