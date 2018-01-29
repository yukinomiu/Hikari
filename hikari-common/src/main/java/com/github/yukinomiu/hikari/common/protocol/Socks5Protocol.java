package com.github.yukinomiu.hikari.common.protocol;

/**
 * Yukinomiu
 * 2017/12/27
 */
public class Socks5Protocol {
    private Socks5Protocol() {
    }

    // version
    public static final byte VERSION_SOCKS5 = 0x05;

    // address type
    public static final byte ADDRESS_TYPE_IPV4 = 0X01;
    public static final byte ADDRESS_TYPE_DOMAIN = 0X03;
    public static final byte ADDRESS_TYPE_IPV6 = 0X04;

    // auth method
    public static final byte AUTH_METHOD_NO_AUTH = 0x00;

    // req command
    public static final byte REQ_COMMAND_CONNECT = 0X01;
    public static final byte REQ_COMMAND_BIND = 0X02;
    public static final byte REQ_COMMAND_UDP_ASSOCIATE = 0X03;

    // req rep status
    public static final byte REQ_REPLAY_SUCCEEDED = 0x00;
    public static final byte REQ_REPLAY_GENERAL_FAILURE = 0x01;
    public static final byte REQ_REPLAY_CONNECTION_NOT_ALLOWED = 0x02;
    public static final byte REQ_REPLAY_NETWORK_UNREACHABLE = 0x03;
    public static final byte REQ_REPLAY_HOST_UNREACHABLE = 0x04;
    public static final byte REQ_REPLAY_CONNECTION_REFUSED = 0x05;
    public static final byte REQ_REPLAY_TTL_EXPIRED = 0x06;
    public static final byte REQ_REPLAY_COMMAND_NOT_SUPPORTED = 0x07;
    public static final byte REQ_REPLAY_ADDRESS_TYPE_NOT_SUPPORTED = 0x08;
}
