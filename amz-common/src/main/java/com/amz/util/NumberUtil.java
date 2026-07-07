package com.amz.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 小红书号工具类
 */
public class NumberUtil {
    public static Long getNumber() {
        // 获取当前时间的时间戳（毫秒级）- 13位
        long timestampMillis = System.currentTimeMillis();
        
        // 添加4位随机数，保证并发不重复（毫秒级时间戳 + 4位随机数 = 17位数字）
        int random = ThreadLocalRandom.current().nextInt(1000, 9999);
        
        return timestampMillis * 10000 + random;
    }
}
