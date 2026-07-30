package com.amz.aspect;

import com.amz.annotation.FieldPermission;
import com.amz.context.UserContext;
import com.amz.result.Result;
import com.amz.service.FieldPermissionService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 字段级数据权限 AOP 切面单元测试。
 * <p>
 * 覆盖场景：
 * <ul>
 *   <li>role 未注入 → 直接返回原值，不做过滤</li>
 *   <li>Result&lt;DTO&gt;：隐藏字段命中 → 反射置 null + hiddenFields 回填</li>
 *   <li>Result&lt;DTO&gt;：无隐藏字段 → 原值不变，hiddenFields 保持 null</li>
 *   <li>Result&lt;List&lt;DTO&gt;&gt;：集合内每个元素均被过滤</li>
 *   <li>Collection&lt;DTO&gt;（非 Result 包装）：每个元素被过滤</li>
 *   <li>普通对象返回（非 Result 非 Collection）：直接过滤</li>
 *   <li>JDK 类型（如 String）→ 跳过过滤</li>
 *   <li>切面异常被吞：FieldPermissionService 抛异常时仍返回原业务值</li>
 *   <li>pjp.proceed() 抛异常应向上传播（不被切面吞掉）</li>
 * </ul>
 */
@DisplayName("FieldPermissionAspect 字段权限切面测试")
class FieldPermissionAspectTest {

    private FieldPermissionService fieldPermissionService;
    private FieldPermissionAspect aspect;
    private ProceedingJoinPoint pjp;

    /** 测试用实体：标注 @FieldPermission 的字段才会被过滤 */
    static class TestDto {
        private String name;
        @FieldPermission
        private String phone;
        @FieldPermission
        private BigDecimal price;
        // 未标注的字段不受切面影响

        public TestDto(String name, String phone, String price) {
            this.name = name;
            this.phone = phone;
            this.price = price == null ? null : new BigDecimal(price);
        }

        public String getName() { return name; }
        public String getPhone() { return phone; }
        public BigDecimal getPrice() { return price; }
    }

    @BeforeEach
    void setUp() {
        fieldPermissionService = mock(FieldPermissionService.class);
        pjp = mock(ProceedingJoinPoint.class);

        aspect = new FieldPermissionAspect();
        ReflectionTestUtils.setField(aspect, "fieldPermissionService", fieldPermissionService);
        UserContext.setRole("VIEWER");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("role 未注入（null）→ 直接返回原值，不做过滤")
    void testNoRoleSkipsFiltering() throws Throwable {
        UserContext.clear(); // 清除 role
        TestDto dto = new TestDto("Alice", "13800000000", "99.9");
        when(pjp.proceed()).thenReturn(dto);

        Object result = aspect.around(pjp);

        assertSame(dto, result);
        assertEquals("13800000000", dto.getPhone(), "无 role 时不应过滤字段");
    }

    @Test
    @DisplayName("Result<DTO>：phone 命中隐藏集合 → 置 null + hiddenFields 回填")
    void testResultDtoHiddenFieldNulled() throws Throwable {
        TestDto dto = new TestDto("Alice", "13800000000", "99.9");
        Result<TestDto> wrapped = Result.success(dto);
        when(pjp.proceed()).thenReturn(wrapped);
        when(fieldPermissionService.getHiddenFields("VIEWER", "TestDto"))
                .thenReturn(new HashSet<>(Collections.singletonList("phone")));

        Object result = aspect.around(pjp);

        assertSame(wrapped, result);
        assertNull(dto.getPhone(), "phone 应被置 null");
        assertEquals("Alice", dto.getName(), "name 未标注不应被过滤");
        assertNotNull(wrapped.getHiddenFields());
        assertTrue(wrapped.getHiddenFields().contains("phone"), "hiddenFields 应包含 phone");
    }

    @Test
    @DisplayName("Result<DTO>：隐藏集合为空 → 字段保持原值，hiddenFields 保持 null")
    void testResultDtoNoHiddenFields() throws Throwable {
        TestDto dto = new TestDto("Alice", "13800000000", "99.9");
        Result<TestDto> wrapped = Result.success(dto);
        when(pjp.proceed()).thenReturn(wrapped);
        when(fieldPermissionService.getHiddenFields("VIEWER", "TestDto"))
                .thenReturn(Collections.emptySet());

        Object result = aspect.around(pjp);

        assertSame(wrapped, result);
        assertEquals("13800000000", dto.getPhone(), "无隐藏规则时不应置 null");
        assertNull(wrapped.getHiddenFields(), "无字段被过滤时 hiddenFields 应保持 null");
    }

    @Test
    @DisplayName("Result<List<DTO>>：集合内每个元素的字段均被过滤")
    void testResultListOfDtosAllFiltered() throws Throwable {
        TestDto dto1 = new TestDto("A", "111", "10");
        TestDto dto2 = new TestDto("B", "222", "20");
        List<TestDto> list = Arrays.asList(dto1, dto2);
        Result<List<TestDto>> wrapped = Result.success(list);
        when(pjp.proceed()).thenReturn(wrapped);
        when(fieldPermissionService.getHiddenFields("VIEWER", "TestDto"))
                .thenReturn(new HashSet<>(Arrays.asList("phone", "price")));

        aspect.around(pjp);

        assertNull(dto1.getPhone());
        assertNull(dto1.getPrice());
        assertNull(dto2.getPhone());
        assertNull(dto2.getPrice());
        // hiddenFields 应包含两个字段名
        Set<String> hidden = new HashSet<>(wrapped.getHiddenFields());
        assertTrue(hidden.contains("phone"));
        assertTrue(hidden.contains("price"));
    }

    @Test
    @DisplayName("Collection<DTO>（非 Result 包装）：每个元素被过滤")
    void testCollectionOfDtosFiltered() throws Throwable {
        TestDto dto1 = new TestDto("A", "111", "10");
        TestDto dto2 = new TestDto("B", "222", "20");
        List<TestDto> list = Arrays.asList(dto1, dto2);
        when(pjp.proceed()).thenReturn(list);
        when(fieldPermissionService.getHiddenFields("VIEWER", "TestDto"))
                .thenReturn(new HashSet<>(Collections.singletonList("phone")));

        Object result = aspect.around(pjp);

        assertSame(list, result);
        assertNull(dto1.getPhone());
        assertNull(dto2.getPhone());
    }

    @Test
    @DisplayName("普通对象返回（非 Result 非 Collection）：直接过滤")
    void testPlainObjectFiltered() throws Throwable {
        TestDto dto = new TestDto("Alice", "13800000000", "99.9");
        when(pjp.proceed()).thenReturn(dto);
        when(fieldPermissionService.getHiddenFields("VIEWER", "TestDto"))
                .thenReturn(new HashSet<>(Collections.singletonList("phone")));

        Object result = aspect.around(pjp);

        assertSame(dto, result);
        assertNull(dto.getPhone());
    }

    @Test
    @DisplayName("JDK 类型返回值（如 String）→ 跳过过滤，不抛异常")
    void testJdkTypeSkipped() throws Throwable {
        when(pjp.proceed()).thenReturn("plain string");
        when(fieldPermissionService.getHiddenFields("VIEWER", "String"))
                .thenReturn(new HashSet<>(Collections.singletonList("value")));

        // 不应抛异常
        Object result = aspect.around(pjp);
        assertEquals("plain string", result);
    }

    @Test
    @DisplayName("null 返回值 → 安全跳过")
    void testNullResultSafe() throws Throwable {
        when(pjp.proceed()).thenReturn(null);

        Object result = aspect.around(pjp);
        assertNull(result);
    }

    @Test
    @DisplayName("切面异常被吞：FieldPermissionService 抛异常时仍返回原业务值")
    void testFilterExceptionSwallowed() throws Throwable {
        TestDto dto = new TestDto("Alice", "13800000000", "99.9");
        Result<TestDto> wrapped = Result.success(dto);
        when(pjp.proceed()).thenReturn(wrapped);
        when(fieldPermissionService.getHiddenFields("VIEWER", "TestDto"))
                .thenThrow(new RuntimeException("Redis 不可用"));

        // 切面吞异常，返回原业务值
        Object result = aspect.around(pjp);
        assertSame(wrapped, result);
        // phone 未被过滤（异常前可能已置 null 也可能未置，关键是业务返回值不受影响）
        assertEquals("99.9", dto.getPrice().toString());
    }

    @Test
    @DisplayName("pjp.proceed() 抛异常 → 应向上传播，不被切面吞掉")
    void testProceedExceptionPropagated() throws Throwable {
        when(pjp.proceed()).thenThrow(new RuntimeException("业务异常"));

        assertThrows(RuntimeException.class, () -> aspect.around(pjp));
    }

    @Test
    @DisplayName("多字段同时命中隐藏集合 → 全部置 null 且 hiddenFields 去重合并")
    void testMultipleFieldsHiddenAndMerged() throws Throwable {
        TestDto dto = new TestDto("Alice", "13800000000", "99.9");
        Result<TestDto> wrapped = Result.success(dto);
        // 预置已有 hiddenFields，验证合并去重
        wrapped.setHiddenFields(Arrays.asList("phone"));
        when(pjp.proceed()).thenReturn(wrapped);
        when(fieldPermissionService.getHiddenFields("VIEWER", "TestDto"))
                .thenReturn(new HashSet<>(Arrays.asList("phone", "price")));

        aspect.around(pjp);

        assertNull(dto.getPhone());
        assertNull(dto.getPrice());
        // 合并后去重，phone 只出现一次
        long phoneCount = wrapped.getHiddenFields().stream().filter("phone"::equals).count();
        assertEquals(1, phoneCount, "已存在的 hiddenFields 应去重合并");
        assertTrue(wrapped.getHiddenFields().contains("price"));
    }
}
