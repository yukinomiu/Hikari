package com.github.yukinomiu.hikari.server;

import com.github.yukinomiu.hikari.common.LifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Yukinomiu
 * 2018/1/22
 */
public class Server implements LifeCycle {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    private final ServerConfig serverConfig;

    private List<Worker> workerList;

    public Server(final ServerConfig serverConfig) {
        this.serverConfig = serverConfig;
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

        String listenAddress = serverConfig.getListenAddress();
        List<Integer> listenPortList = serverConfig.getListenPortList();
        workerList = new ArrayList<>(listenPortList.size());

        for (Integer listenPort : listenPortList) {
            // open selector and channel
            Selector selector = Selector.open();
            ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.configureBlocking(false);

            // bind address
            SocketAddress socketAddress = new InetSocketAddress(listenAddress, listenPort);
            serverSocketChannel.bind(socketAddress);
            logger.info("listen on {}:{}", listenAddress, listenPort);

            // register
            SelectionKey key = serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
            Thread thread = new Thread(new ServerMainLoop(key));

            Worker worker = new Worker(key, thread);
            workerList.add(worker);
        }
    }

    private void destroy() throws IOException {
        logger.info("destroy resource");

        for (Worker worker : workerList) {
            worker.selectionKey.cancel();
            worker.selectionKey.selector().close();
            worker.selectionKey.channel().close();
        }
    }

    private void run() {
        for (Worker worker : workerList) {
            worker.thread.start();
        }
    }

    private void stop() {
        for (Worker worker : workerList) {
            worker.thread.interrupt();
        }
    }

    private class Worker {
        private final SelectionKey selectionKey;
        private final Thread thread;

        private Worker(final SelectionKey selectionKey, final Thread thread) {
            this.selectionKey = selectionKey;
            this.thread = thread;
        }
    }

    private class ServerMainLoop implements Runnable {
        private final SelectionKey selectionKey;

        private ServerMainLoop(final SelectionKey selectionKey) {
            this.selectionKey = selectionKey;
        }

        @Override
        public void run() {
            logger.info("server thread<{}> run...", Thread.currentThread().getName());

            final ServerHandler serverHandler = new ServerHandler(serverConfig);
            final Selector selector = selectionKey.selector();

            // loop
            while (true) {
                try {
                    int count = selector.select();

                    if (Thread.currentThread().isInterrupted()) {
                        logger.info("server thread {} exit loop", Thread.currentThread().getId());
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
                            serverHandler.handleRead(key);
                        }
                        else if (key.isAcceptable()) {
                            serverHandler.handleAccept(key);
                        }
                        else if (key.isConnectable()) {
                            serverHandler.handleConnect(key);
                        }
                    }
                } catch (Exception e) {
                    logger.error("server main loop exception", e);
                }
            }

            logger.info("server thread {} stop", Thread.currentThread().getId());
        }
    }
}
