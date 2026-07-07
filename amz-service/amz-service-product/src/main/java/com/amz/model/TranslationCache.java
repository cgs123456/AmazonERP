package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 翻译缓存（按 SHA-256 哈希 + 源语言 + 目标语言唯一）。
 */
@Data
@TableName("amz_translation_cache")
public class TranslationCache implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("source_text_hash")
    private String sourceTextHash;

    @TableField("source_lang")
    private String sourceLang;

    @TableField("target_lang")
    private String targetLang;

    @TableField("source_text")
    private String sourceText;

    @TableField("translated_text")
    private String translatedText;

    @TableField("create_time")
    private LocalDateTime createTime;
}
