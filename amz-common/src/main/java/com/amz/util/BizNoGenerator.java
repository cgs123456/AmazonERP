package com.amz.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 业务单号生成器。
 * <p>
 * 格式：前缀 + 毫秒时间戳 + 3 位随机数（如 PO1718012345678007），保留时间可读性。
 * <p>
 * 背景：此前各服务直接用 {@code 前缀 + System.currentTimeMillis()} 生成单号，
 * 高并发同毫秒下必然碰撞；而 PO/出入库/货件等单号列多有唯一约束，碰撞即 500。
 * 后缀随机数将碰撞概率降至可忽略水平。如需更强保证（多实例高并发），
 * 应升级为雪花 ID / UUID（会改变单号格式，需评估下游解析依赖）。
 */
public final class BizNoGenerator {

    private BizNoGenerator() {
    }

    public static String next(String prefix) {
        return prefix + System.currentTimeMillis()
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }
}
