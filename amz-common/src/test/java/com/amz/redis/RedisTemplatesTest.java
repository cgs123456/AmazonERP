package com.amz.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 共享 RedisTemplate 配方的行为。
 * <p>
 * 这段配方此前在 4 个服务里各抄一遍，改一处就会与另外三处分叉（序列化器一旦不一致，
 * 同一个 key 在不同服务里写读就会互相看不懂）。这里把"唯一实现"钉成可测行为。
 */
@DisplayName("Redis 序列化配方：key 用 String、value 用 JSON，且真能带类型往返")
class RedisTemplatesTest {

    private final RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
    private final RedisTemplate<String, Object> template = RedisTemplates.jsonStringValues(factory);

    @Test
    @DisplayName("序列化器组合与 4 份副本原先手写的一致")
    void serializersMatchThePreviousCopies() {
        assertSame(factory, template.getConnectionFactory(), "连接工厂必须由调用方传入并原样设置");
        assertEquals(RedisSerializer.string(), template.getKeySerializer());
        assertEquals(RedisSerializer.string(), template.getHashKeySerializer());
        assertTrue(template.getValueSerializer() instanceof GenericJacksonJsonRedisSerializer);
        assertTrue(template.getHashValueSerializer() instanceof GenericJacksonJsonRedisSerializer);
    }

    @Test
    @DisplayName("value 序列化带类型信息往返：跨服务读同一 key 才不会退化成 Map")
    void valueSerializerRoundTripsWithTypeId() {
        RedisSerializer<Object> value = serializer(template.getValueSerializer());
        byte[] bytes = value.serialize(new Payload("k1", 7));
        Object back = value.deserialize(bytes);
        assertEquals(new Payload("k1", 7), back, "丢了 @class 就会读成 LinkedHashMap");
    }

    @Test
    @DisplayName("key 就是 UTF-8 字符串，不是 JDK 序列化的乱码 key")
    void keySerializerIsPlainString() {
        RedisSerializer<Object> key = serializer(template.getKeySerializer());
        byte[] bytes = key.serialize("amz:demo");
        assertArrayEquals("amz:demo".getBytes(java.nio.charset.StandardCharsets.UTF_8), bytes);
        assertEquals("amz:demo", key.deserialize(bytes));
    }

    @Test
    @DisplayName("Map 值按 JSON 存回，不额外包一层")
    void mapPayloadSurvives() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("n", 3);
        RedisSerializer<Object> hashValue = serializer(template.getHashValueSerializer());
        byte[] bytes = hashValue.serialize(value);
        assertEquals(value, hashValue.deserialize(bytes));
    }

    /** getter 声明成 RedisSerializer&lt;?&gt;，测试里按 Object 用即可（序列化器本身类型无关）。 */
    @SuppressWarnings("unchecked")
    private static RedisSerializer<Object> serializer(RedisSerializer<?> serializer) {
        return (RedisSerializer<Object>) serializer;
    }

    /** 需要无参构造 + getter/setter，Jackson 才能带类型还原。 */
    public static class Payload {
        private String id;
        private int count;

        public Payload() {
        }

        Payload(String id, int count) {
            this.id = id;
            this.count = count;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Payload
                    && java.util.Objects.equals(id, ((Payload) other).id)
                    && count == ((Payload) other).count;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(id, count);
        }
    }
}
