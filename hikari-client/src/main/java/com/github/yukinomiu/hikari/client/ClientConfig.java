package com.github.yukinomiu.hikari.client;

import com.github.yukinomiu.hikari.common.HikariConfig;

import java.util.List;

/**
 * Yukinomiu
 * 2018/01/22
 */
public class ClientConfig extends HikariConfig {
    private String listenAddress;
    private Integer listenPort;
    private Boolean localDnsResolve;

    private String serverAddress;
    private List<Integer> serverPortList;
    private String privateKey;

    public String getListenAddress() {
        return listenAddress;
    }

    public void setListenAddress(String listenAddress) {
        this.listenAddress = listenAddress;
    }

    public Integer getListenPort() {
        return listenPort;
    }

    public void setListenPort(Integer listenPort) {
        this.listenPort = listenPort;
    }

    public Boolean getLocalDnsResolve() {
        return localDnsResolve;
    }

    public void setLocalDnsResolve(Boolean localDnsResolve) {
        this.localDnsResolve = localDnsResolve;
    }

    public String getServerAddress() {
        return serverAddress;
    }

    public void setServerAddress(String serverAddress) {
        this.serverAddress = serverAddress;
    }

    public List<Integer> getServerPortList() {
        return serverPortList;
    }

    public void setServerPortList(List<Integer> serverPortList) {
        this.serverPortList = serverPortList;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }
}
