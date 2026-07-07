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
        // 1.获取热度前10名
        Set<Object> hots = redisTemplate.opsForZSet().reverseRange(RedisConstant.NOTE_SCORE, 0, 9);
        if (CollectionUtils.isEmpty(hots)) {
            return Result.success(null);
        }
        List<Map<String, Object>> hotList = hots.stream().map(hot -> {
            Double score = redisTemplate.opsForZSet().score(RedisConstant.NOTE_SCORE, hot);
            Map<String, Object> map = new HashMap<>();
            map.put("key", hot);
            map.put("score", score);
            return map;
        }).collect(Collectors.toList());
        log.info("热搜前十名是:{}", hotList);
        return Result.success(hotList);
    }
}
