package com.github.yukinomiu.hikari.client;

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
        ConfigLoader<ClientConfig> configLoader = new ConfigLoader<>(configFilePath);
        ClientConfig clientConfig;
        try {
            clientConfig = configLoader.load(ClientConfig.class);
        } catch (HikariException e) {
            logger.error("loading config exception", e);
            return;
        }
        logger.info("load config success");



        // start client
        final Client client = new Client(clientConfig);
        try {
            logger.info("start client");
            client.start();
        } catch (IOException e) {
            logger.error("client start exception", e);
        }

        // register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                logger.info("shutdown client");
                client.shutdown();
            } catch (IOException e) {
                logger.error("client shutdown exception", e);
            }
        }));
    }
}
