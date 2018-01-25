package com.github.yukinomiu.hikari.client;

import com.github.yukinomiu.hikari.common.HikariContext;

/**
 * Yukinomiu
 * 2018/1/24
 */
public abstract class ClientContext implements HikariContext {
    private final ClientContextType type;

    protected ClientContext(final ClientContextType type) {
        this.type = type;
    }

    public ClientContextType getType() {
        return type;
    }
}
