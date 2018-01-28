package com.github.yukinomiu.hikari.common;

import com.github.yukinomiu.hikari.common.crypto.CryptoManager;
import com.github.yukinomiu.hikari.common.crypto.HikariCrypto;
import com.github.yukinomiu.hikari.common.exception.HikariChecksumFailException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.zip.CRC32;

/**
 * Yukinomiu
 * 2018/1/26
 */
public abstract class HikariAbstractHandle implements HikariHandle {
    private static final Logger logger = LoggerFactory.getLogger(HikariAbstractHandle.class);
    private final CRC32 crc32 = new CRC32();

    private final HikariCrypto hikariCrypto;

    protected HikariAbstractHandle(final HikariConfig hikariConfig) {
        // crypto
        final String encryptType = hikariConfig.getEncryptType();
        final String secret = hikariConfig.getSecret();
        hikariCrypto = CryptoManager.getCrypto(encryptType, secret);
        logger.info("using {}", encryptType);
    }

    protected final boolean read(final SocketChannel srcChannel, final ByteBuffer dstBuffer, final HikariContext context) throws IOException {
        dstBuffer.clear();
        int read = srcChannel.read(dstBuffer);

        if (read == -1) {
            context.close();
            return false;
        }
        else if (read == 0) {
            return false;
        }

        dstBuffer.flip();
        return true;
    }

    protected final void write(final HikariContext srcContext, final HikariContext dstContext, final ByteBuffer srcBuffer) throws IOException {
        final SelectionKey dstKey = dstContext.key();
        final SocketChannel dstChannel = (SocketChannel) dstKey.channel();

        dstChannel.write(srcBuffer);
        if (srcBuffer.hasRemaining()) {
            // under buffer full
            final SelectionKey srcKey = srcContext.key();
            final ByteBuffer writeBuffer = dstContext.writeBuffer();

            srcKey.interestOps(srcKey.interestOps() & ~SelectionKey.OP_READ);
            dstKey.interestOps(dstKey.interestOps() | SelectionKey.OP_WRITE);

            writeBuffer.clear();
            writeBuffer.put(srcBuffer);
            writeBuffer.flip();
        }
    }

    protected final void encrypt(final ByteBuffer srcBuffer, final ByteBuffer encBuffer, final ByteBuffer dstBuffer) {
        // encrypt
        encBuffer.clear();
        hikariCrypto.encrypt(srcBuffer, encBuffer);
        encBuffer.flip();

        // warp
        dstBuffer.clear();
        wrapPacket(encBuffer, dstBuffer);
        dstBuffer.flip();
    }

    protected final boolean decrypt(final ByteBuffer srcBuffer, final ByteBuffer encBuffer, final ByteBuffer dstBuffer, final PacketContext packetContext) {
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
                return decrypt(srcBuffer, encBuffer, dstBuffer, packetContext);
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
                    final int checksum;
                    if (packetBuffer.position() < 4) {
                        while (packetBuffer.position() != 4) {
                            packetBuffer.put(srcBuffer.get());
                        }

                        packetBuffer.flip();
                        checksum = packetBuffer.getInt();

                        encBuffer.clear();
                        encBuffer.put(srcBuffer);
                        encBuffer.flip();
                    }
                    else {
                        packetBuffer.flip();
                        checksum = packetBuffer.getInt();

                        encBuffer.clear();
                        encBuffer.put(packetBuffer);
                        encBuffer.put(srcBuffer);
                        encBuffer.flip();
                    }

                    verifyChecksum(checksum, encBuffer);

                    dstBuffer.clear();
                    hikariCrypto.decrypt(encBuffer, dstBuffer);
                    dstBuffer.flip();

                    packetContext.clear();
                    return true;
                }
                else {
                    // more than one packet
                    final int backupLimit = srcBuffer.limit();
                    srcBuffer.limit(srcBuffer.position() + leftLength);

                    final int checksum;
                    if (packetBuffer.position() < 4) {
                        while (packetBuffer.position() != 4) {
                            packetBuffer.put(srcBuffer.get());
                        }

                        packetBuffer.flip();
                        checksum = packetBuffer.getInt();

                        encBuffer.clear();
                        encBuffer.put(srcBuffer);
                        encBuffer.flip();

                        srcBuffer.limit(backupLimit);
                    }
                    else {
                        packetBuffer.flip();
                        checksum = packetBuffer.getInt();

                        encBuffer.clear();
                        encBuffer.put(packetBuffer);
                        encBuffer.put(srcBuffer);
                        encBuffer.flip();

                        srcBuffer.limit(backupLimit);
                    }

                    verifyChecksum(checksum, encBuffer);

                    dstBuffer.clear();
                    hikariCrypto.decrypt(encBuffer, dstBuffer);
                    dstBuffer.flip();

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

                    encBuffer.clear();
                    encBuffer.put(srcBuffer);
                    encBuffer.flip();

                    dstBuffer.clear();
                    hikariCrypto.decrypt(encBuffer, dstBuffer);
                    dstBuffer.flip();

                    return true;
                }
                else {
                    // more than one packet
                    final int backupLimit = srcBuffer.limit();
                    srcBuffer.limit(srcBuffer.position() + length);

                    final int checksum = srcBuffer.getInt();
                    verifyChecksum(checksum, srcBuffer);

                    encBuffer.clear();
                    encBuffer.put(srcBuffer);
                    encBuffer.flip();

                    srcBuffer.limit(backupLimit);

                    dstBuffer.clear();
                    hikariCrypto.decrypt(encBuffer, dstBuffer);
                    dstBuffer.flip();

                    return true;
                }
            }
        }
    }

    private void wrapPacket(final ByteBuffer srcBuffer, final ByteBuffer dstBuffer) {
        short length = (short) (srcBuffer.remaining() + 4);

        // checksum
        final int backupPosition = srcBuffer.position();
        crc32.reset();
        crc32.update(srcBuffer);
        final int checksum = (int) crc32.getValue();
        srcBuffer.position(backupPosition);

        dstBuffer.putShort(length);
        dstBuffer.putInt(checksum);
        dstBuffer.put(srcBuffer);
    }

    private void verifyChecksum(final int expectedChecksum, final ByteBuffer buffer) {
        final int positionBackup = buffer.position();

        crc32.reset();
        crc32.update(buffer);
        final int realChecksum = (int) crc32.getValue();

        if (expectedChecksum != realChecksum) {
            throw new HikariChecksumFailException("checksum fail");
        }

        buffer.position(positionBackup);
    }
}
