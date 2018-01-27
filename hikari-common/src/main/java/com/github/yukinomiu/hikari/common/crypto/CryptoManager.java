package com.github.yukinomiu.hikari.common.crypto;

import com.github.yukinomiu.hikari.common.exception.HikariRuntimeException;

/**
 * Yukinomiu
 * 2018/1/27
 */
public class CryptoManager {
    private CryptoManager() {
    }

    public static HikariCrypto getCrypto(final String encryptType, final String secret) {
        if (encryptType == null || encryptType.trim().length() == 0) {
            throw new HikariRuntimeException("encrypt type can not be empty");
        }

        if (secret == null || secret.trim().length() == 0) {
            throw new HikariRuntimeException("secret can not be empty");
        }

        switch (encryptType) {
            case "plain":
                return new PlainCrypto();

            case "aes":
                return new AESCrypto(secret);

            case "rc4":
                return new RC4Crypto(secret);

            default:
                throw new HikariRuntimeException(String.format("encrypt type '%s' not support", encryptType));
        }
    }
}
