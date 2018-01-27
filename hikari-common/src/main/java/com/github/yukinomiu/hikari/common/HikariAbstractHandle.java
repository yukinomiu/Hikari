package com.github.yukinomiu.hikari.common;

import com.github.yukinomiu.hikari.common.crypto.AESCrypto;
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

    protected final ByteBuffer dataBuffer;
    private final ByteBuffer cryptoBuffer;
    private final ByteBuffer packetBuffer;

    private final HikariCrypto hikariCrypto;

    protected HikariAbstractHandle(final HikariConfig hikariConfig) {
        // buffer
        final Integer bufferSize = hikariConfig.getBufferSize();
        dataBuffer = ByteBuffer.allocateDirect(bufferSize);
        cryptoBuffer = ByteBuffer.allocateDirect(bufferSize);
        packetBuffer = ByteBuffer.allocateDirect(bufferSize + HikariConstant.PACKET_WRAPPER_SIZE);

        // crypto
        hikariCrypto = new AESCrypto("secret");
    }

    @Override
    public final void handleRead(final SelectionKey selectionKey) {
        final SocketChannel socketChannel = (SocketChannel) selectionKey.channel();
        final HikariContext hikariContext = (HikariContext) selectionKey.attachment();

        try {
            if (hikariContext instanceof EncryptTransfer) {
                final EncryptTransfer encryptTransfer = (EncryptTransfer) hikariContext;

                // read
                packetBuffer.clear();
                if (!read(socketChannel, packetBuffer, hikariContext)) {
                    return;
                }
                packetBuffer.flip();

                // unwrap packet and decrypt
                final PacketContext packetContext = encryptTransfer.getPacketContext();

                cryptoBuffer.clear();
                while (unwrapPacket(packetBuffer, cryptoBuffer, packetContext)) {
                    cryptoBuffer.flip();

                    // decrypt
                    dataBuffer.clear();
                    hikariCrypto.decrypt(cryptoBuffer, dataBuffer);
                    dataBuffer.flip();

                    consumeData(selectionKey, socketChannel, dataBuffer, hikariContext);
                    cryptoBuffer.clear();
                }
            }
            else {
                // read
                dataBuffer.clear();
                if (!read(socketChannel, dataBuffer, hikariContext)) {
                    return;
                }
                dataBuffer.flip();

                // handle plain
                consumeData(selectionKey, socketChannel, dataBuffer, hikariContext);
            }
        } catch (HikariChecksumFailException e) {
            logger.warn("checksum verifying fail");
            hikariContext.close();
        } catch (Exception e) {
            logger.warn("handle read exception: {}", e.getMessage(), e);
            hikariContext.close();
        }
    }

    protected abstract void consumeData(final SelectionKey selectionKey, final SocketChannel socketChannel, final ByteBuffer data, final HikariContext hikariContext) throws Exception;

    protected final void encryptWrite(final ByteBuffer dataBuffer, final SocketChannel targetChannel) throws IOException {
        // encrypt
        cryptoBuffer.clear();
        hikariCrypto.encrypt(dataBuffer, cryptoBuffer);
        cryptoBuffer.flip();

        // warp
        packetBuffer.clear();
        wrapPacket(cryptoBuffer, packetBuffer);
        packetBuffer.flip();

        // write
        targetChannel.write(packetBuffer);
    }

    protected final PacketContext getNewPacketContext() {
        return new PacketContext(packetBuffer.capacity());
    }

    private boolean read(final SocketChannel srcChannel, final ByteBuffer dstBuffer, final HikariContext context) throws IOException {
        int read = srcChannel.read(dstBuffer);

        if (read == -1) {
            context.close();
            return false;
        }
        else if (read == 0) {
            return false;
        }

        return true;
    }

    private void wrapPacket(final ByteBuffer srcBuffer, final ByteBuffer dstBuffer) {
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

    private boolean unwrapPacket(final ByteBuffer srcBuffer, final ByteBuffer dstBuffer, final PacketContext packetContext) {
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
