package com.github.yukinomiu.hikari.common.util;

/**
 * Yukinomiu
 * 2017/7/13
 */
public final class HexUtil {
    private HexUtil() {
    }

    public static String hexString(final byte[] input) {
        StringBuilder sb = new StringBuilder(input.length << 1);
        for (byte v : input) {
            sb.append(String.format("%02x", v));
        }
        return sb.toString();
    }
}
