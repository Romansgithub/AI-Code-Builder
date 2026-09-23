package com.quan.aicodehelper.core.rag;

import cn.hutool.crypto.digest.DigestUtil;

/**
 * 文件切块后的片段。片段原文须与磁盘逐字一致；向量化文本另加位置语义，见 toEmbeddingText。
 */
public record CodeSlice(
        String filePath,
        int chunkIndex,
        int startLine,
        int endLine,
        String content,
        String contentHash,
        String fileHash,
        String symbols
) {

    /** 补上位置语义后的嵌入文本；不用注释语法包裹，避免误写回文件时破坏代码。 */
    public String toEmbeddingText() {
        return "[文件] " + filePath + " [行] " + startLine + "-" + endLine + "\n" + content;
    }

    /** 展示给模型与日志用的位置标签。 */
    public String locationLabel() {
        return filePath + "（第 " + startLine + "-" + endLine + " 行）";
    }

    /** 片段内容摘要，供增量比对与去重。 */
    public static String hashOf(String content) {
        return DigestUtil.sha256Hex(content == null ? "" : content);
    }
}
