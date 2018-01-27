package com.github.yukinomiu.hikari.common.crypto;

import java.nio.ByteBuffer;

/**
 * Yukinomiu
 * 2018/1/27
 */
public class PlainCrypto implements HikariCrypto {
    @Override
    public void encrypt(ByteBuffer input, ByteBuffer output) {
        output.put(input);
    }

    @Override
    public void decrypt(ByteBuffer input, ByteBuffer output) {
        output.put(input);
    }
}
