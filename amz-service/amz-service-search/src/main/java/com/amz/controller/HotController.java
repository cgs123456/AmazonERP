package com.amz.controller;

import com.amz.result.Result;
import com.amz.service.HotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/search")
public class HotController {

    @Autowired
    private HotService hotService;


    @GetMapping("/getHotList")
    public Result<List<Map<String, Object>>> getHotList() {
        return hotService.getHotList();
    }
}
