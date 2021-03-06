package com.github.yukinomiu.hikari.common;

import java.nio.ByteBuffer;

/**
 * Yukinomiu
 * 2018/1/26
 */
public class PacketContext {
    private final ByteBuffer packetBuffer;

    private Short currentPacketLength;
    private boolean buffering;

    public PacketContext(final Integer bufferSize) {
        this.packetBuffer = ByteBuffer.allocateDirect(bufferSize + HikariConstant.PACKET_WRAPPER_SIZE);

        currentPacketLength = null;
        buffering = false;
    }

    public void clear() {
        packetBuffer.clear();

        currentPacketLength = null;
        buffering = false;
    }

    public ByteBuffer getPacketBuffer() {
        return packetBuffer;
    }

    public Short getCurrentPacketLength() {
        return currentPacketLength;
    }

    public void setCurrentPacketLength(Short currentPacketLength) {
        this.currentPacketLength = currentPacketLength;
    }

    public boolean isBuffering() {
        return buffering;
    }

    public void startBuffering() {
        this.buffering = true;
    }
}
