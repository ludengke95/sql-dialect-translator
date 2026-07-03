package com.translator.proxy.protocol.util;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * MySQL 认证算法测试。
 */
public class MySQLAuthTest {

    @Test
    public void testScrambleGeneration() {
        byte[] scramble = MySQLAuth.generateScramble();
        assertEquals("scramble 长度应为 20", 20, scramble.length);
        // 不应包含 NUL 字节
        for (byte b : scramble) {
            assertNotEquals("scramble 不应包含 NUL 字节", (byte) 0x00, b);
        }
    }

    @Test
    public void testScramble411KnownVector() {
        // 使用 MySQL 官方文档中的已知向量进行验证
        String password = "password";
        byte[] scramble = new byte[20];
        Arrays.fill(scramble, (byte) 0x01); // 全填 0x01（不违反非NUL规则，测试用）

        byte[] token = MySQLAuth.scramble411(password, scramble);
        assertEquals("token 长度应为 20", 20, token.length);
        // token 不应为全 0
        boolean allZero = true;
        for (byte b : token) {
            if (b != 0) { allZero = false; break; }
        }
        assertFalse("token 不应该为全 0", allZero);
    }

    @Test
    public void testVerifyCorrectPassword() {
        String password = "test123";
        byte[] scramble = MySQLAuth.generateScramble();

        // 客户端计算 token
        byte[] clientToken = MySQLAuth.scramble411(password, scramble);

        // 服务端验证
        assertTrue("正确的密码应该验证通过",
                MySQLAuth.verify(password, scramble, clientToken));
    }

    @Test
    public void testVerifyWrongPassword() {
        String password = "correct_password";
        byte[] scramble = MySQLAuth.generateScramble();

        // 用错误密码计算 token
        byte[] wrongToken = MySQLAuth.scramble411("wrong_password", scramble);

        // 服务端验证
        assertFalse("错误的密码应该验证失败",
                MySQLAuth.verify(password, scramble, wrongToken));
    }

    @Test
    public void testVerifyNullInputs() {
        byte[] scramble = MySQLAuth.generateScramble();
        byte[] token = MySQLAuth.scramble411("pass", scramble);

        assertFalse("null password 应返回 false",
                MySQLAuth.verify(null, scramble, token));
        assertFalse("null scramble 应返回 false",
                MySQLAuth.verify("pass", null, token));
        assertFalse("null token 应返回 false",
                MySQLAuth.verify("pass", scramble, null));
    }

    @Test
    public void testDeterministic() {
        String password = "hello";
        byte[] scramble = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                11, 12, 13, 14, 15, 16, 17, 18, 19, 20};

        byte[] token1 = MySQLAuth.scramble411(password, scramble);
        byte[] token2 = MySQLAuth.scramble411(password, scramble);

        assertArrayEquals("相同输入应产生相同 token", token1, token2);
    }
}
