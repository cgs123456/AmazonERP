package com.amz.constant;

public class RedisConstant {
    public static final String PHONE_CODE = "amz:user:phone_code:";
    public static final String ATTENTION_CACHE = "amz:user:attention_cache:";
    public static final String NOTE_GEO = "amz:note:note_geo:";
    public static final String NOTE_DETAIL_CACHE = "amz:note:note_detail_cache:";
    public static final String NOTE_SCORE = "amz:note:note_score:";
    public static final String LIKE_SET_CACHE = "amz:note:like_set_cache:";
    public static final String COLLECTION_SET_CACHE = "amz:note:collection_set_cache:";
    public static final String USER_BLOOM_FILTER = "amz:note:user_bloom_filter:";
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
