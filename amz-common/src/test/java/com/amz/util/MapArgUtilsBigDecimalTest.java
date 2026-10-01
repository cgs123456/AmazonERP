package com.amz.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link MapArgUtils} 两个 BigDecimal 出口的语义边界。
 * <p>
 * 存在动因：全仓曾有 6 份私有 {@code toBigDecimal(Object)}，分别落在"null→null"和
 * "null→ZERO"两种语义上，光看方法名分不清调用点要哪种 —— 财务路径上把"未知"当"0"
 * 会凭空造出账实相符的假象。现在把两种语义显式化为两个重载并各钉一条断言，
 * 各模块私有副本统一走这里。
 * <p>
 * 精度口径：一律经 {@code new BigDecimal(String)}，不得经 double 中转
 * （0.1 会变成 0.100000000000000005551115123125782702118159341144561767578125）。
 */
@DisplayName("MapArgUtils：null 语义与默认值语义不得混用")
class MapArgUtilsBigDecimalTest {

    @Test
    @DisplayName("无默认值的重载把「拿不到数」如实返回 null")
    void nullReturningOverloadKeepsUnknownAsNull() {
        assertNull(MapArgUtils.toBigDecimal((Object) null));
        assertNull(MapArgUtils.toBigDecimal("not-a-number"));
        assertNull(MapArgUtils.toBigDecimal(new HashMap<String, Object>(), "missing"));
    }

    @Test
    @DisplayName("带默认值的重载才允许把缺失折算成 0")
    void defaultedOverloadOnlyFallsBackWhenToldTo() {
        assertEquals(BigDecimal.ZERO, MapArgUtils.toBigDecimal(null, BigDecimal.ZERO));
        assertEquals(BigDecimal.ZERO, MapArgUtils.toBigDecimal("abc", BigDecimal.ZERO));
        assertEquals(BigDecimal.ZERO,
                MapArgUtils.toBigDecimal(new HashMap<String, Object>(), "missing", BigDecimal.ZERO));
        assertEquals(new BigDecimal("7.25"),
                MapArgUtils.toBigDecimal(new BigDecimal("7.25"), BigDecimal.ZERO));
    }

    @Test
    @DisplayName("BigDecimal 原样透传（不复制、不改标度），数字与字符串按字符串精确解析")
    void precisionIsNotRoutedThroughDouble() {
        BigDecimal source = new BigDecimal("0.10");
        assertSame(source, MapArgUtils.toBigDecimal(source));
        assertEquals(new BigDecimal("0.10"), MapArgUtils.toBigDecimal("0.10"));
        assertEquals(new BigDecimal("0.1"), MapArgUtils.toBigDecimal(Double.valueOf(0.1)));
        assertEquals(new BigDecimal("250"), MapArgUtils.toBigDecimal(Integer.valueOf(250)));
    }

    @Test
    @DisplayName("Map 取值形式与 Object 形式语义一致")
    void mapAndObjectFormsAgree() {
        Map<String, Object> args = new HashMap<>();
        args.put("amount", "12.34");
        assertEquals(MapArgUtils.toBigDecimal(args.get("amount")), MapArgUtils.toBigDecimal(args, "amount"));
    }
}
