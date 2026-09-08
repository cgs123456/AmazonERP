package com.amz.ai.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

/**
 * 文档文本提取器（Tika 自动识别：PDF / Word / Markdown / TXT / HTML）。
 * <p>
 * Markdown/TXT 本质也是 Tika 的纯文本路径统一处理，不做特殊分支，
 * 避免“扩展名路由”与内容嗅探不一致。超大文件由调用方限大小。
 */
@Slf4j
@Component
public class DocumentParser {

    private final Tika tika = new Tika();

    /**
     * 提取纯文本。filename 仅用于格式嗅探（扩展名），可为 null。
     *
     * @throws IllegalArgumentException 解析失败或结果为空
     */
    public String parse(byte[] bytes, String filename) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文档内容为空");
        }
        try {
            Metadata metadata = new Metadata();
            if (filename != null && !filename.isBlank()) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
            }
            String text = tika.parseToString(new ByteArrayInputStream(bytes), metadata);
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("文档未解析出文本内容: " + filename);
            }
            return text;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("文档解析失败 filename={}", filename, e);
            throw new IllegalArgumentException("文档解析失败: " + filename, e);
        }
    }
}
