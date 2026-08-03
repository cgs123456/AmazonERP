package com.amz.controller;

import com.amz.annotation.ShopScoped;
import com.amz.result.Result;
import com.amz.service.MessageSyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/message/v2")
public class MessageController {

    @Autowired
    private MessageSyncService messageSyncService;

    @PostMapping("/sync/{shopId}")
    @ShopScoped
    public Result<List<Map<String, Object>>> syncMessages(@PathVariable Long shopId, @RequestParam String marketplaceId) {
        return Result.success(messageSyncService.syncMessages(shopId, marketplaceId));
    }

    @GetMapping("/list/{shopId}")
    @ShopScoped
    public Result<List<Map<String, Object>>> listMessages(@PathVariable Long shopId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(messageSyncService.listLocalMessages(shopId, page, pageSize));
    }

    @PostMapping("/reply/{shopId}/{messageId}")
    @ShopScoped
    public Result<String> replyMessage(@PathVariable Long shopId, @PathVariable String messageId, @RequestBody String body) {
        boolean ok = messageSyncService.replyMessage(shopId, messageId, body);
        return ok ? Result.success("回复成功") : Result.failure("回复失败");
    }
}
