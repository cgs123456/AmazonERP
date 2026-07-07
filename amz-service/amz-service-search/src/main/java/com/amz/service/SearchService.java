package com.amz.service;

import com.amz.model.vo.NoteVo;
import com.amz.result.Result;

import java.util.List;

public interface SearchService {
    Result<List<NoteVo>> search(String key);
}
