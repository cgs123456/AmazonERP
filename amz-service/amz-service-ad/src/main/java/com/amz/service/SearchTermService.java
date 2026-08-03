package com.amz.service;

import com.amz.model.AdSearchTerm;
import com.amz.model.ConvertingTerm;
import com.amz.model.AdAsinKeyword;

import java.util.List;
import java.util.Map;

/**
 * 搜索词分析服务接口。
 */
public interface SearchTermService {

    /** 保存搜索词报表数据 */
    AdSearchTerm saveSearchTerm(AdSearchTerm searchTerm);

    /** 查询店铺搜索词列表 */
    List<AdSearchTerm> listSearchTerms(Long shopId, String campaignId, String searchTerm);

    /** 搜索词分析：高ACoS/低CR/出单词/浪费词 */
    Map<String, Object> analyzeSearchTerms(Long shopId, String campaignId, Integer days);

    /** 自动提取出单词到词库 */
    List<ConvertingTerm> extractConvertingTerms(Long shopId, Integer days);

    /** 查询出单词库 */
    List<ConvertingTerm> listConvertingTerms(Long shopId, String asin);

    /** 搜索词聚类分析（按词根分组） */
    Map<String, Object> clusterSearchTerms(Long shopId, String campaignId, Integer days);

    /** ASIN 关键词反查 */
    List<AdAsinKeyword> reverseLookupAsin(Long shopId, String asin);

    /** 保存 ASIN 关键词反查数据 */
    List<AdAsinKeyword> saveAsinKeywords(List<AdAsinKeyword> keywords);
}
