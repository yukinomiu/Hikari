package com.github.yukinomiu.hikari.common.crypto;

import java.nio.ByteBuffer;

/**
 * Yukinomiu
 * 2018/1/26
 */
public interface HikariCrypto {
    void encrypt(final ByteBuffer input, final ByteBuffer output);

    void decrypt(final ByteBuffer input, final ByteBuffer output);
}
