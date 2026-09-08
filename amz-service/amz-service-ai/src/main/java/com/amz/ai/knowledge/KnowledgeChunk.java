package com.amz.ai.knowledge;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库文本块（含检索评分）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeChunk {

    /** 所属文档 ID（上传时生成的 UUID） */
    private String docId;

    /** 原文件名 */
    private String filename;

    /** 块序号（文档内从 0 开始） */
    private int chunkIndex;

    /** 文本内容 */
    private String content;

    /** 检索评分（RRF 融合分或重排分，无评分场景为 null） */
    private Double score;
}
