package com.amz.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务单号生成器单元测试。
 */
@DisplayName("业务单号生成器单元测试")
class BizNoGeneratorTest {

    @Test
    @DisplayName("格式为 前缀+毫秒时间戳+3位随机数")
    void testFormat() {
        String no = BizNoGenerator.next("PO");
        assertTrue(no.matches("^PO\\d{16}$"), "单号格式应为 PO+13位毫秒+3位随机，实际=" + no);
    }

    @Test
    @DisplayName("连续生成应基本不重复（同毫秒靠随机后缀区分）")
    void testMostlyUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            seen.add(BizNoGenerator.next("T"));
        }
        // 允许极小概率碰撞，阈值留足余量
        assertTrue(seen.size() > 80, "100 次生成去重后应 >80，实际=" + seen.size());
    }
}
