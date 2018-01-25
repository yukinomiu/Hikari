package com.github.yukinomiu.hikari.server;

import java.util.List;

/**
 * Yukinomiu
 * 2018/01/22
 */
public class ServerConfig {
    private String listenAddress;
    private List<Integer> listenPortList;
    private Integer bufferSize;
    private List<String> privateKeyList;

    public String getListenAddress() {
        return listenAddress;
    }

    public void setListenAddress(String listenAddress) {
        this.listenAddress = listenAddress;
    }

    public List<Integer> getListenPortList() {
        return listenPortList;
    }

    public void setListenPortList(List<Integer> listenPortList) {
        this.listenPortList = listenPortList;
    }

    public Integer getBufferSize() {
        return bufferSize;
    }

    public void setBufferSize(Integer bufferSize) {
        this.bufferSize = bufferSize;
    }

    public List<String> getPrivateKeyList() {
        return privateKeyList;
    }

    public void setPrivateKeyList(List<String> privateKeyList) {
        this.privateKeyList = privateKeyList;
    }
}
