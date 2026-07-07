package com.amz.service;

import com.amz.result.Result;
import com.amz.model.pojo.History;

import java.util.List;

public interface HistoryService {
    Result<List<History>> getHistoryList();

    Result<Void> deleteHistory();
}
