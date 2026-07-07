package com.amz.controller;

import com.amz.model.vo.ProductVo;
import com.amz.result.Result;
import com.amz.service.SearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品搜索 REST 端点。
 */
@RestController
@RequestMapping("/search")
public class SearchController {

    @Autowired
    private SearchService searchService;

    @GetMapping("/search/{key}")
    public Result<List<ProductVo>> search(@PathVariable String key) {
        return searchService.search(key);
    }
}
