package com.hioas.sms.expr;

import java.security.SecureRandom;

/**
 * 运行时来源：时钟与随机数。可注入以便测试复现确定性签名。
 */
public interface RuntimeSource {

    long epochSecond();

    long epochMilli();

    String uuid();

    /** n 位随机数字 */
    String randomDigits(int n);

    /** n 位随机字母数字 */
    String randomAlnum(int n);

    static RuntimeSource system() {
        return new SystemRuntimeSource();
    }
}

final class SystemRuntimeSource implements RuntimeSource {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] DIGITS = "0123456789".toCharArray();
    private static final char[] ALNUM =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    @Override
    public long epochSecond() {
        return System.currentTimeMillis() / 1000;
    }

    @Override
    public long epochMilli() {
        return System.currentTimeMillis();
    }

    @Override
    public String uuid() {
        return java.util.UUID.randomUUID().toString();
    }

    @Override
    public String randomDigits(int n) {
        return random(DIGITS, n);
    }

    @Override
    public String randomAlnum(int n) {
        return random(ALNUM, n);
    }

    private String random(char[] pool, int n) {
        if (n <= 0) {
            throw new com.hioas.sms.core.HioasSmsException("随机长度必须大于0: " + n);
        }
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(pool[RANDOM.nextInt(pool.length)]);
        }
        return sb.toString();
    }
}
