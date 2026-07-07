package com.amz.constant;

/**
 * Redis Key 常量。
 * <p>
 * 已清理社交场景的 NOTE/LIKE/COLLECTION 相关 key，
 * 替换为 ERP 商品搜索相关 key。
 */
public class RedisConstant {
    public static final String PHONE_CODE = "amz:user:phone_code:";
    public static final String PRODUCT_SCORE = "amz:product:product_score:";
    public static final String PRODUCT_DETAIL_CACHE = "amz:product:product_detail_cache:";
    public static final String USER_BLOOM_FILTER = "amz:user:user_bloom_filter:";
    public static final String PRODUCT_STOCK_CACHE = "amz:product:stock:cache:";

    // ===== Shopping Agent 相关 =====
    /** 多轮对话存储前缀，完整 key: agent:conversation:{conversationId} */
    public final static String AGENT_CONVERSATION = "agent:conversation:";
    /** Few-Shot 成功样本列表 key */
    public final static String AGENT_FEWSHOT_SUCCESS = "agent:fewshot:success";
    /** Few-Shot 失败样本列表 key */
    public final static String AGENT_FEWSHOT_FAILURE = "agent:fewshot:failure";
    /** 格式正确率计数器（hash: total / success） */
    public final static String AGENT_FORMAT_STATS = "agent:format:stats";
}
