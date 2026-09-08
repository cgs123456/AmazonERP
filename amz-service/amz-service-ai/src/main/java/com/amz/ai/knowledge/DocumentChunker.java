package com.amz.ai.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * 定长滑动窗口分块器（B-1）。
 * <p>
 * 策略：空白归一化后按字符切分，窗口 800、步长 700（100 重叠），保证语义不断裂太多；
 * 纯工具类、无状态、可单测。 intentionally 简单：SOP 文档以段落阅读为主，
 * 固定窗口 + 重叠已够用，语义分块（如按标题）待真实语料验证后再演进。
 */
public final class DocumentChunker {

    /** 窗口字符数 */
    public static final int CHUNK_SIZE = 800;
    /** 步长字符数（窗口 - 重叠） */
    public static final int CHUNK_STEP = 700;

    private DocumentChunker() {
    }

    /**
     * 分块。空白（连续换行/制表/多空格）先归一化；空输入返回空列表。
     */
    public static List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null) {
            return chunks;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
        if (normalized.isEmpty()) {
            return chunks;
        }
        if (normalized.length() <= CHUNK_SIZE) {
            chunks.add(normalized);
            return chunks;
        }
        for (int start = 0; start < normalized.length(); start += CHUNK_STEP) {
            int end = Math.min(start + CHUNK_SIZE, normalized.length());
            chunks.add(normalized.substring(start, end));
            if (end >= normalized.length()) {
                break;
            }
        }
        return chunks;
    }
}
