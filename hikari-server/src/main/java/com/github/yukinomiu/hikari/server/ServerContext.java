package com.github.yukinomiu.hikari.server;

import com.github.yukinomiu.hikari.common.HikariContext;

/**
 * Yukinomiu
 * 2018/1/22
 */
public abstract class ServerContext implements HikariContext {
    private final ServerContextType type;

    protected ServerContext(final ServerContextType type) {
        this.type = type;
    }

    public ServerContextType getType() {
        return type;
    }
}
