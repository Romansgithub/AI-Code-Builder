package com.quan.aicodehelper.core.rag;

/**
 * 一条检索结果。保留最终分、向量分、关键词分三项，便于出问题时区分「向量没召回」还是「关键词没召回」。
 */
public record RetrievedSlice(
        String filePath,
        int startLine,
        int endLine,
        String content,
        double score,
        double vectorScore,
        double keywordScore
) {

    /** 展示给模型的位置标签。 */
    public String locationLabel() {
        return filePath + "（第 " + startLine + "-" + endLine + " 行）";
    }

    /** 渲染进上下文时占用的字符估算长度。 */
    public int renderedLength() {
        return content == null ? 0 : content.length();
    }
}
