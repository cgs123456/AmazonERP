package com.amz.service;

import com.amz.mapper.FeedResultErrorMapper;
import com.amz.model.FeedIssue;
import com.amz.model.FeedResult;
import com.amz.model.FeedResultErrorEntity;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 将 processing report 中可诊断的 issue 规范化落库。
 * <p>
 * 这里不保存 report 原文或 raw row；每次成功下载后按 feedId 原子替换明细，
 * 这样历史报告被修正时旧错误不会继续污染查询结果。
 */
@Service
public class FeedResultErrorStore {

    private final FeedResultErrorMapper mapper;

    public FeedResultErrorStore(FeedResultErrorMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public void replace(Long shopId, FeedResult result) {
        if (shopId == null || result == null || result.getFeedId() == null) {
            throw new IllegalArgumentException("shopId and feed result are required");
        }

        QueryWrapper<FeedResultErrorEntity> oldRows = new QueryWrapper<>();
        oldRows.eq("shop_id", shopId).eq("feed_id", result.getFeedId());
        mapper.delete(oldRows);

        LocalDateTime now = LocalDateTime.now();
        int issueIndex = 0;
        for (FeedIssue issue : result.getIssues()) {
            FeedResultErrorEntity entity = new FeedResultErrorEntity();
            entity.setFeedId(result.getFeedId());
            entity.setShopId(shopId);
            entity.setIssueIndex(issueIndex++);
            entity.setRowIndex(issue.getRowIndex());
            entity.setSellerSku(issue.getSellerSku());
            entity.setErrorCode(issue.getErrorCode());
            entity.setSeverity(issue.getSeverity());
            entity.setErrorMessage(issue.getErrorMessage());
            entity.setCreateTime(now);
            mapper.insert(entity);
        }
    }
}
