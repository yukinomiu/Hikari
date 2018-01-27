package com.github.yukinomiu.hikari.common.util;

import com.github.yukinomiu.hikari.common.exception.HikariRuntimeException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Yukinomiu
 * 2018/1/27
 */
public class Md5Util {

    private final MessageDigest messageDigest;

    public static Md5Util getInstance() {
        return new Md5Util();
    }

    private Md5Util() {
        try {
            messageDigest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new HikariRuntimeException("get MD5 message digest exception", e);
        }
    }

    public byte[] md5(final byte[] input) {
        messageDigest.reset();
        messageDigest.update(input);
        return messageDigest.digest();
    }

    public byte[] md5(final String input) {
        return md5(input.getBytes(StandardCharsets.UTF_8));
    }

    public String md5String(final byte[] input) {
        byte[] md5 = md5(input);
        return HexUtil.hexString(md5);
    }

    public String md5String(final String input) {
        return md5String(input.getBytes(StandardCharsets.UTF_8));
    }

    public static void main(String[] args) {
        String a = "hikari";
        byte[] hash = Md5Util.getInstance().md5(a.getBytes(StandardCharsets.UTF_8));

        System.out.println(HexUtil.hexString(hash));
    }
}
