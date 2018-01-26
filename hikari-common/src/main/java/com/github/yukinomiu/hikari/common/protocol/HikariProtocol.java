package com.github.yukinomiu.hikari.common.protocol;

/**
 * Yukinomiu
 * 2018/1/24
 */
public class HikariProtocol {
    private HikariProtocol() {
    }

    // version
    public static final byte VERSION_HIKARI1 = 0x01;

    // encrypt type
    public static final byte ENCRYPT_PLAIN = 0x00;

    // address type
    public static final byte ADDRESS_TYPE_IPV4 = 0x00;
    public static final byte ADDRESS_TYPE_IPV6 = 0x01;
    public static final byte ADDRESS_TYPE_DOMAIN = 0x02;

    // auth response
    public static final byte AUTH_RESPONSE_OK = 0x00;
    public static final byte AUTH_RESPONSE_VERSION_NOT_SUPPORT = 0x01;
    public static final byte AUTH_RESPONSE_AUTH_FAIL = 0x02;
    public static final byte AUTH_RESPONSE_ENCRYPT_TYPE_NOT_SUPPORT = 0x03;
    public static final byte AUTH_RESPONSE_DNS_RESOLVE_FAIL = 0x04;
    public static final byte AUTH_RESPONSE_CONNECT_TARGET_FAIL = 0x05;
}
