package com.github.yukinomiu.hikari.common.protocol;

/**
 * Yukinomiu
 * 2017/12/27
 */
public class Socks4Protocol {
    private Socks4Protocol() {
    }

    // version
    public static final byte VERSION_SOCKS4 = 0x04;

    // req command
    public static final byte REQ_COMMAND_CONNECT = 0X01;
    public static final byte REQ_COMMAND_BIND = 0X02;

    // req rep
    public static final byte REQ_REPLAY_VN = 0x00;

    public static final byte REQ_REPLAY_GRANTED = 0x5A;
    public static final byte REQ_REPLAY_REJECTED_OR_FAILED = 0x5B;
    public static final byte REQ_REPLAY_CONNECTION_TARGET_FAIL = 0x5C;
    public static final byte REQ_REPLAY_BAD_USER_ID = 0x5D;

    public static final byte REQ_REPLAY_NULL = 0x00;
}
