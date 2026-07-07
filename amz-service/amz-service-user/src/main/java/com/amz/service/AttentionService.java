package com.amz.service;

import com.amz.result.Result;
import com.amz.model.pojo.Attention;

import java.util.List;

public interface AttentionService {
    Result<Integer> isAttention(Integer otherId);
    Result<Void> attention(Integer otherId);
    Result<List<Attention>> getAttention(Integer userId);
}
