package com.github.yukinomiu.hikari.common.crypto;

import com.github.yukinomiu.hikari.common.util.HexUtil;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Yukinomiu
 * 2018/1/27
 */
@Ignore
public class CryptoTest {

    final String secret = "hikari-crypto-secret";

    @Test
    public void test() {
        final HikariCrypto hikariCrypto = new RC4Crypto(secret);

        final String plainText = "hello, hikari";
        final ByteBuffer plainBytes = ByteBuffer.wrap(plainText.getBytes(StandardCharsets.UTF_8));
        final ByteBuffer encryptBytes = ByteBuffer.allocate(plainBytes.capacity());

        // encrypt
        hikariCrypto.encrypt(plainBytes, encryptBytes);

        // decrypt
        encryptBytes.flip();
        plainBytes.clear();
        hikariCrypto.decrypt(encryptBytes, plainBytes);

        final String result = new String(plainBytes.array(), StandardCharsets.UTF_8);
        System.out.println(String.format("plain: %s", plainText));
        System.out.println(String.format("secret: %s", HexUtil.hexString(encryptBytes.array())));
        System.out.println(String.format("result: %s", result));

        Assert.assertEquals(plainText, result);
    }

    @Test
    public void benchmark() {
        runBenchmark(new RC4Crypto(secret));
        runBenchmark(new AESCrypto(secret));
    }

    private void runBenchmark(final HikariCrypto crypto) {
        // example data
        final byte[] example = new byte[2048];
        for (int i = 0; i < example.length; i++) {
            example[i] = (byte) (Math.random() * 100);
        }

        final ByteBuffer src = ByteBuffer.wrap(secret.getBytes(StandardCharsets.UTF_8));
        final ByteBuffer dst = ByteBuffer.allocateDirect(src.capacity());

        // encrypt
        long es = System.currentTimeMillis();
        for (int i = 0; i < 1000000; i++) {
            src.position(0);
            dst.clear();
            crypto.encrypt(src, dst);
        }
        long ee = System.currentTimeMillis();

        dst.flip();
        long ds = System.currentTimeMillis();
        for (int i = 0; i < 1000000; i++) {
            dst.position(0);
            src.clear();
            crypto.decrypt(dst, src);
        }
        long de = System.currentTimeMillis();

        System.out.println(String.format("%s encrypt 1000000 count: %s ms", crypto.getClass().getSimpleName(), ee - es));
        System.out.println(String.format("%s decrypt 1000000 count: %s ms", crypto.getClass().getSimpleName(), de - ds));
    }
}
