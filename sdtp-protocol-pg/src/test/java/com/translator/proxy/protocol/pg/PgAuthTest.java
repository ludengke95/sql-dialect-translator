package com.translator.proxy.protocol.pg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * PgAuth 单元测试：MD5 口令算法与校验。
 *
 * <p>算法与真实 PostgreSQL 客户端一致：
 * <pre>
 *   inner = md5(password + user)            // 32 hex
 *   outer = md5(inner + saltBytes)          // salt 为 4 个原始字节
 *   client 发送 "md5" + outer
 * </pre>
 */
public class PgAuthTest {

    private static byte[] salt(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    public void md5TokenStartsWithMd5AndHasCorrectLength() {
        String token = PgAuth.md5Password("postgres", "secret", salt("abcd"));
        assertTrue(token.startsWith("md5"));
        assertEquals(35, token.length()); // "md5" + 32 hex
        String hex = token.substring(3);
        assertTrue(hex.matches("[0-9a-f]{32}"));
    }

    @Test
    public void verifyMd5SucceedsForCorrectPassword() {
        byte[] s = salt("wxyz");
        String token = PgAuth.md5Password("postgres", "secret", s);
        assertTrue(PgAuth.verifyMd5(token, "postgres", "secret", s));
    }

    @Test
    public void verifyMd5FailsForWrongPassword() {
        byte[] s = salt("wxyz");
        String token = PgAuth.md5Password("postgres", "secret", s);
        assertFalse(PgAuth.verifyMd5(token, "postgres", "wrong", s));
    }

    @Test
    public void verifyMd5FailsForWrongUser() {
        byte[] s = salt("wxyz");
        String token = PgAuth.md5Password("postgres", "secret", s);
        assertFalse(PgAuth.verifyMd5(token, "other", "secret", s));
    }

    @Test
    public void md5TokenIsDeterministicForSameInputs() {
        byte[] s = salt("abcd");
        String a = PgAuth.md5Password("postgres", "secret", s);
        String b = PgAuth.md5Password("postgres", "secret", s);
        assertEquals(a, b);
    }

    @Test
    public void cleartextPasswordTokenEqualsPlaintext() {
        // 明文模式下客户端直接发送密码原文，不含 "md5" 前缀
        String token = PgAuth.cleartextPassword("hunter2");
        assertEquals("hunter2", token);
    }
}
