package com.amz;

import com.amz.constant.RedisConstant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Set;

@SpringBootTest
class AmzServiceSearchApplicationTests {

    @Autowired
    private RedisTemplate redisTemplate;

    @Test
    void testRedis() {
        Set<Object> hots = redisTemplate.opsForZSet().reverseRange(RedisConstant.NOTE_SCORE, 0, 9);
    }

}
