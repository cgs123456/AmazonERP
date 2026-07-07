package com.amz.controller;

import com.amz.model.vo.NoteVo;
import com.amz.result.Result;
import com.amz.service.SearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/search")
public class SearchController {

    @Autowired
    private SearchService searchService;

    @GetMapping("/search/{key}")
    public Result<List<NoteVo>> search(@PathVariable String key) throws IOException {
        return searchService.search(key);
    }
}
