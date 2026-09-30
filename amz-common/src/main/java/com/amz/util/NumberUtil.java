package com.amz.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 用户号工具类
 */
public class NumberUtil {
    public static Long getNumber() {
        // 获取当前时间的时间戳（毫秒级）- 13位
        long timestampMillis = System.currentTimeMillis();

        // 4 位随机数只能降低同毫秒碰撞概率，不能保证不重复：同一毫秒内约 9000 个取值，
        // 一对调用撞上的概率约 1/9000。需要强唯一的编号请依赖数据库唯一约束或号段分配，
        // 不要把本方法的返回值当业务主键。（全仓实测：本类当前无任何调用方）
        int random = ThreadLocalRandom.current().nextInt(1000, 9999);

        return timestampMillis * 10000 + random;
    }
}
