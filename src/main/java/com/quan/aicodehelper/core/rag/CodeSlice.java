package com.quan.aicodehelper.core.rag;

import cn.hutool.crypto.digest.DigestUtil;

/**
 * 一个代码片段 —— 「文件 → 片段」这一层的产物，也是索引与检索之间流转的载体。
 *
 * <p><b>为什么 content 与「送给向量化的文本」是两个东西</b>：
 * 片段原文必须与磁盘逐字一致（它会被原样展示给模型，也会参与一致性判断）；
 * 而向量化时希望带上「哪个文件、第几行」这类位置语义 ——
 * 同一段 {@code setup()} 代码在十个文件里可能语义相近，带上路径才能把它们区分开。
 * 两者混成一个字段，就会污染片段原文。所以分成 {@link #content} 与
 * {@link #toEmbeddingText()}。</p>
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

    /**
     * 面向嵌入模型补上位置语义后的文本。
     *
     * <p>刻意不用 {@code //} 之类注释语法包起来 —— 这段文本只进模型、
     * 不进文件；一旦带了注释符号，它在 HTML / Vue 模板里就是非法语法，
     * 将来若被某条路径误写回文件就会直接破坏代码。</p>
     */
    public String toEmbeddingText() {
        return "[文件] " + filePath + " [行] " + startLine + "-" + endLine + "\n" + content;
    }

    /**
     * 展示给模型与日志用的位置标签。
     */
    public String locationLabel() {
        return filePath + "（第 " + startLine + "-" + endLine + " 行）";
    }

    /**
     * 计算片段内容摘要，供增量比对与去重使用。
     */
    public static String hashOf(String content) {
        return DigestUtil.sha256Hex(content == null ? "" : content);
    }
}
