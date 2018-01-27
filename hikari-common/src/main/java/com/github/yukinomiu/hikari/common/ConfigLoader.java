package com.github.yukinomiu.hikari.common;

import com.github.yukinomiu.hikari.common.exception.HikariException;
import com.github.yukinomiu.hikari.common.util.JsonUtil;

import java.io.*;


/**
 * Yukinomiu
 * 2018/1/22
 */
public class ConfigLoader<T> {
    private final String configFilePath;

    public ConfigLoader(final String configFilePath) {
        this.configFilePath = configFilePath;
    }

    public T load(final Class<T> cls) throws HikariException {
        if (configFilePath == null || configFilePath.length() == 0) {
            throw new HikariException("config file path can not be blank");
        }

        File configFile = new File(configFilePath);
        if (!configFile.exists()) {
            throw new HikariException(String.format("config file '%s' not exists", configFilePath));
        }
        if (!configFile.isFile()) {
            throw new HikariException(String.format("config file '%s' must be file", configFilePath));
        }

        try (InputStream ins = new FileInputStream(configFile)) {
            return JsonUtil.deserialize(ins, cls);
        } catch (FileNotFoundException e) {
            throw new HikariException(String.format("config file '%s' not exists", configFilePath));
        } catch (IOException e) {
            throw new HikariException("read config file IO exception", e);
        }
    }
}
