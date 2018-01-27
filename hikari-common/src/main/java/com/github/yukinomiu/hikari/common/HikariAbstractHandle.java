package com.github.yukinomiu.hikari.common;

import com.github.yukinomiu.hikari.common.crypto.HikariCrypto;
import com.github.yukinomiu.hikari.common.exception.HikariChecksumFailException;
import com.github.yukinomiu.hikari.common.exception.HikariDecryptException;
import com.github.yukinomiu.hikari.common.exception.HikariEncryptException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.zip.CRC32;

/**
 * Yukinomiu
 * 2018/1/26
 */
public abstract class HikariAbstractHandle implements HikariHandle {

    private final CRC32 crc32 = new CRC32();

    protected boolean read(final SocketChannel srcChannel, final ByteBuffer dstBuffer, final HikariContext context) throws IOException {
        // dstBuffer.clear();
        int read = srcChannel.read(dstBuffer);

        if (read == -1) {
            context.close();
            return false;
        }
        else if (read == 0) {
            return false;
        }

        // dstBuffer.flip();
        return true;
    }

    protected void encrypt(final ByteBuffer srcBuffer, final ByteBuffer dstBuffer, final HikariCrypto hikariCrypto) throws HikariEncryptException {
        // dstBuffer.clear();
        hikariCrypto.encrypt(srcBuffer, dstBuffer);
        // dstBuffer.flip();
    }

    protected void decrypt(final ByteBuffer srcBuffer, final ByteBuffer dstBuffer, final HikariCrypto hikariCrypto) throws HikariDecryptException {
        // dstBuffer.clear();
        hikariCrypto.decrypt(srcBuffer, dstBuffer);
        // dstBuffer.flip();
    }

    protected void wrapPacket(final ByteBuffer srcBuffer, final ByteBuffer dstBuffer) {
        short length = (short) (srcBuffer.remaining() + 4);

        // checksum
        final int backupPosition = srcBuffer.position();
        crc32.reset();
        crc32.update(srcBuffer);
        final int checksum = (int) crc32.getValue();
        srcBuffer.position(backupPosition);

        // dstBuffer.clear();
        dstBuffer.putShort(length);
        dstBuffer.putInt(checksum);
        dstBuffer.put(srcBuffer);
        // dstBuffer.flip();
    }

    protected boolean unwrapPacket(final ByteBuffer srcBuffer, final ByteBuffer dstBuffer, final PacketContext packetContext) {
        if (srcBuffer.remaining() == 0) {
            return false;
        }

        if (packetContext.isBuffering()) {
            // packet body
            Short length = packetContext.getCurrentPacketLength();

            if (length == null) {
                // try finish packet length
                ByteBuffer packetBuffer = packetContext.getPacketBuffer();
                packetBuffer.put(srcBuffer.get());
                packetBuffer.flip();
                length = packetBuffer.getShort();

                packetBuffer.clear();
                packetContext.setCurrentPacketLength(length);
                return unwrapPacket(srcBuffer, dstBuffer, packetContext);
            }
            else {
                // length known
                ByteBuffer packetBuffer = packetContext.getPacketBuffer();
                final int leftLength = length - packetBuffer.position();

                final int currentRemaining = srcBuffer.remaining();
                if (currentRemaining < leftLength) {
                    // continue buffering
                    packetBuffer.put(srcBuffer);
                    return false;
                }
                else if (currentRemaining == leftLength) {
                    // just full packet
                    packetBuffer.put(srcBuffer);
                    packetBuffer.flip();
                    final int checksum = packetBuffer.getInt();
                    verifyChecksum(checksum, packetBuffer);

                    // dstBuffer.clear();
                    dstBuffer.put(packetBuffer);
                    // dstBuffer.flip();

                    packetContext.clear();
                    return true;
                }
                else {
                    // more than one packet
                    final int backupLimit = srcBuffer.limit();
                    srcBuffer.limit(srcBuffer.position() + leftLength);

                    packetBuffer.put(srcBuffer);
                    packetBuffer.flip();
                    final int checksum = packetBuffer.getInt();
                    verifyChecksum(checksum, packetBuffer);

                    srcBuffer.limit(backupLimit);

                    // dstBuffer.clear();
                    dstBuffer.put(packetBuffer);
                    // dstBuffer.flip();

                    packetContext.clear();
                    return true;
                }
            }
        }
        else {
            // packet head
            if (srcBuffer.remaining() == 1) {
                // length unknown
                packetContext.startBuffering();

                ByteBuffer packetBuffer = packetContext.getPacketBuffer();
                packetBuffer.put(srcBuffer.get());
                return false;
            }
            else {
                // length known
                final short length = srcBuffer.getShort();

                int currentRemaining = srcBuffer.remaining();
                if (currentRemaining < length) {
                    // piece of packet
                    packetContext.startBuffering();

                    packetContext.setCurrentPacketLength(length);
                    ByteBuffer packetBuffer = packetContext.getPacketBuffer();
                    packetBuffer.put(srcBuffer);
                    return false;
                }
                else if (currentRemaining == length) {
                    // full packet
                    final int checksum = srcBuffer.getInt();
                    verifyChecksum(checksum, srcBuffer);

                    //dstBuffer.clear();
                    dstBuffer.put(srcBuffer);
                    // dstBuffer.flip();

                    return true;
                }
                else {
                    // more than one packet
                    final int backupLimit = srcBuffer.limit();
                    srcBuffer.limit(srcBuffer.position() + length);

                    final int checksum = srcBuffer.getInt();
                    verifyChecksum(checksum, srcBuffer);

                    // dstBuffer.clear();
                    dstBuffer.put(srcBuffer);
                    // dstBuffer.flip();

                    srcBuffer.limit(backupLimit);

                    return true;
                }
            }
        }
    }

    private void verifyChecksum(final int expectedChecksum, final ByteBuffer buffer) {
        final int positionBackup = buffer.position();

        crc32.reset();
        crc32.update(buffer);
        final int realChecksum = (int) crc32.getValue();

        buffer.position(positionBackup);

        if (expectedChecksum != realChecksum) {
            throw new HikariChecksumFailException("checksum fail");
        }
    }
}
