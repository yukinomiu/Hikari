package com.github.yukinomiu.hikari.common;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * Yukinomiu
 * 2018/1/22
 */
public interface HikariHandle {
    void handleAccept(final SelectionKey selectionKey);

    void handleConnect(final SelectionKey selectionKey);

    void handleRead(final SelectionKey selectionKey);

    default boolean readToBuffer(final SocketChannel socketChannel, final HikariContext context, final ByteBuffer readBuffer) throws IOException {
        readBuffer.clear();
        int read = socketChannel.read(readBuffer);

        if (read == -1) {
            context.close();
            return false;
        }
        else if (read == 0) {
            return false;
        }

        readBuffer.flip();
        return true;
    }

    default void wrapPacket(final ByteBuffer readBuffer, final ByteBuffer writeBuffer) {
        short length = (short) readBuffer.remaining();

        writeBuffer.clear();
        writeBuffer.putShort(length);
        writeBuffer.put(readBuffer);
        writeBuffer.flip();
    }

    default boolean unwrapPacket(final ByteBuffer readBuffer, final ByteBuffer writeBuffer, final PacketContext packetContext) {
        if (readBuffer.remaining() == 0) {
            return false;
        }

        if (packetContext.isBuffering()) {
            // packet body
            Short length = packetContext.getCurrentPacketLength();

            if (length == null) {
                // try finish packet length
                ByteBuffer packetBuffer = packetContext.getPacketBuffer();
                packetBuffer.put(readBuffer.get());
                packetBuffer.flip();
                length = packetBuffer.getShort();

                packetBuffer.clear();
                packetContext.setCurrentPacketLength(length);
                return unwrapPacket(readBuffer, writeBuffer, packetContext);
            }
            else {
                // length known
                ByteBuffer packetBuffer = packetContext.getPacketBuffer();
                final int leftLength = length - packetBuffer.position();

                int currentRemaining = readBuffer.remaining();
                if (currentRemaining < leftLength) {
                    // continue buffering
                    packetBuffer.put(readBuffer);
                    return false;
                }
                else if (currentRemaining == leftLength) {
                    // just full packet
                    packetBuffer.flip();

                    writeBuffer.clear();
                    writeBuffer.put(packetBuffer);
                    writeBuffer.put(readBuffer);
                    writeBuffer.flip();

                    packetContext.clear();
                    return true;
                }
                else {
                    // more than one packet
                    final int backLimit = readBuffer.limit();
                    readBuffer.limit(readBuffer.position() + leftLength);

                    packetBuffer.flip();

                    writeBuffer.clear();
                    writeBuffer.put(packetBuffer);
                    writeBuffer.put(readBuffer);
                    writeBuffer.flip();

                    readBuffer.limit(backLimit);

                    packetContext.clear();
                    return true;
                }
            }
        }
        else {
            // packet head
            if (readBuffer.remaining() == 1) {
                // length unknown
                packetContext.startBuffering();

                ByteBuffer packetBuffer = packetContext.getPacketBuffer();
                packetBuffer.put(readBuffer.get());
                return false;
            }
            else {
                // length known
                final short length = readBuffer.getShort();

                int currentRemaining = readBuffer.remaining();
                if (currentRemaining < length) {
                    // piece of packet
                    packetContext.startBuffering();

                    packetContext.setCurrentPacketLength(length);
                    ByteBuffer packetBuffer = packetContext.getPacketBuffer();
                    packetBuffer.put(readBuffer);
                    return false;
                }
                else if (currentRemaining == length) {
                    // full packet
                    writeBuffer.clear();
                    writeBuffer.put(readBuffer);
                    writeBuffer.flip();
                    return true;
                }
                else {
                    // more than one packet
                    final int backLimit = readBuffer.limit();
                    readBuffer.limit(readBuffer.position() + length);

                    writeBuffer.clear();
                    writeBuffer.put(readBuffer);
                    writeBuffer.flip();

                    readBuffer.limit(backLimit);
                    return true;
                }
            }
        }
    }
}
