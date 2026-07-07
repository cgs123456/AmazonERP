package com.amz.service;

import com.amz.result.Result;

import java.util.List;
import java.util.Map;

public interface HotService {
    Result<List<Map<String, Object>>> getHotList();
}
