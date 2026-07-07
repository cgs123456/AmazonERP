package com.amz.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 验证码工具类
 */
public class CodeUtil {
    public static String generateCode(int length) {
        StringBuilder sb = new StringBuilder();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }
}
