package com.github.yukinomiu.hikari.server;

import com.github.yukinomiu.hikari.common.ConfigLoader;
import com.github.yukinomiu.hikari.common.exception.HikariException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Yukinomiu
 * 2018/1/22
 */
public class Application {
    private static final Logger logger = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        if (args.length == 0) {
            logger.error("can not get configuration argument");
            return;
        }

        // load config
        final String configFilePath = args[0];
        logger.info("load config: {}", configFilePath);
        ConfigLoader<ServerConfig> configLoader = new ConfigLoader<>(configFilePath);
        ServerConfig serverConfig;
        try {
            serverConfig = configLoader.load(ServerConfig.class);
        } catch (HikariException e) {
            logger.error("loading config exception", e);
            return;
        }
        logger.info("load config success");

        // start server
        final Server server = new Server(serverConfig);
        try {
            logger.info("start server");
            server.start();
        } catch (IOException e) {
            logger.error("server start exception", e);
        }

        // register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                logger.info("shutdown server");
                server.shutdown();
            } catch (IOException e) {
                logger.error("server shutdown exception", e);
            }
        }));
    }
}
