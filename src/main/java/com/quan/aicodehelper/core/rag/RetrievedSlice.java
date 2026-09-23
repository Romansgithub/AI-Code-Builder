package com.quan.aicodehelper.core.rag;

/**
 * 一条检索结果。
 *
 * <p>把三个分数都留着（最终分、向量分、关键词分），而不是只留最终分：
 * 检索质量出问题时，第一个要回答的问题就是「是向量没召回，还是关键词没召回」。
 * 只留一个数就只能靠重新跑一遍来猜 —— 而这恰恰是最难复现的那类问题。</p>
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

    /**
     * 展示给模型的位置标签。
     */
    public String locationLabel() {
        return filePath + "（第 " + startLine + "-" + endLine + " 行）";
    }

    /**
     * 渲染进上下文时占用的字符估算长度。
     */
    public int renderedLength() {
        return content == null ? 0 : content.length();
    }
}
