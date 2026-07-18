package com.translator.proxy.protocol.pg;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * PostgreSQL 认证工具：MD5 / 明文口令算法与认证请求消息构造。
 *
 * <p>MD5 算法与真实 PostgreSQL 客户端一致：
 * <pre>
 *   stage1 = md5(password + user)            // 32 hex
 *   stage2 = md5(stage1 + saltBytes)         // salt 为 4 个原始字节
 *   客户端发送 "md5" + stage2
 * </pre>
 */
public final class PgAuth {

    private PgAuth() {}

    /** MD5 口令前缀 */
    public static final String MD5_PREFIX = "md5";

    /**
     * 计算客户端应发送的 MD5 口令 token。
     *
     * @param user   用户名
     * @param password 密码（明文）
     * @param salt  服务端下发的 4 字节 salt
     * @return "md5" + 32 位十六进制
     */
    public static String md5Password(String user, String password, byte[] salt) {
        String stage1 = md5Hex(password + user);
        String saltStr = new String(salt, StandardCharsets.US_ASCII);
        String stage2 = md5Hex(stage1 + saltStr);
        return MD5_PREFIX + stage2;
    }

    /**
     * 校验客户端发来的 MD5 token 是否正确。
     */
    public static boolean verifyMd5(String clientToken, String user, String password, byte[] salt) {
        if (clientToken == null || !clientToken.startsWith(MD5_PREFIX)) {
            return false;
        }
        String expected = md5Password(user, password, salt);
        return expected.equalsIgnoreCase(clientToken);
    }

    /**
     * 明文模式下，客户端发送的 token 即密码原文。
     */
    public static String cleartextPassword(String token) {
        return token;
    }

    /**
     * 构造 AuthenticationCleartextPassword 请求消息（未加长度帧，由 PgMessageEncoder 帧封装）。
     */
    public static ByteBuf buildAuthenticationCleartext(ByteBufAllocator alloc) {
        ByteBuf b = alloc.buffer(5);
        b.writeByte(PgProtocol.MSG_AUTH_REQUEST);
        b.writeInt(PgProtocol.AUTH_CLEAR_TEXT);
        return b;
    }

    /**
     * 构造 AuthenticationMD5Password 请求消息（含 4 字节 salt）。
     */
    public static ByteBuf buildAuthenticationMd5(ByteBufAllocator alloc, byte[] salt) {
        ByteBuf b = alloc.buffer(9);
        b.writeByte(PgProtocol.MSG_AUTH_REQUEST);
        b.writeInt(PgProtocol.AUTH_MD5);
        b.writeBytes(salt);
        return b;
    }

    private static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm not available", e);
        }
    }
}
