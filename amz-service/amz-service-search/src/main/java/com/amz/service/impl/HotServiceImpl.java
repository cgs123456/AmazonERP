package com.amz.service.impl;

import com.amz.constant.RedisConstant;
import com.amz.result.Result;
import com.amz.service.HotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class HotServiceImpl implements HotService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public Result<List<Map<String, Object>>> getHotList() {
        // 一次 reverseRangeWithScores 同时取回成员与分数（旧实现 1 次 ZREVRANGE + 10 次 ZSCORE）
        Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<Object>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(RedisConstant.PRODUCT_SCORE, 0, 9);
        if (CollectionUtils.isEmpty(tuples)) {
            return Result.success(null);
        }
        List<Map<String, Object>> hotList = tuples.stream().map(t -> {
            Map<String, Object> map = new HashMap<>();
            map.put("key", t.getValue());
            map.put("score", t.getScore());
            return map;
        }).collect(Collectors.toList());
        log.info("热搜前十名是:{}", hotList);
        return Result.success(hotList);
    }
}
