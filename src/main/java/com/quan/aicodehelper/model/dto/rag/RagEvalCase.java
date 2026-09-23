package com.quan.aicodehelper.model.dto.rag;

import com.quan.aicodehelper.model.enums.RetrievalKind;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条检索评测用例。expectedFiles 是人工标注的标准答案：需求落到工程上该改哪个文件，由人读源码判定。
 * 不能用模型先猜一份答案再拿它去评测模型自己的检索，那测的是模型与自己的自洽程度，与检索质量无关。
 */
@Data
public class RagEvalCase {

    /**
     * 用例标识，报告里按它定位。
     */
    private String id;

    /**
     * 引用 {@link RagEvalApp#getKey()} 指明被测应用。
     */
    private String appKey;

    /**
     * 用例类型，决定归入哪个统计分组。
     */
    private RetrievalKind kind;

    /**
     * 用户的修改需求原文，直接作为检索 query。
     */
    private String query;

    /**
     * 期望命中的文件（相对工程根的路径，正斜杠）。允许一条需求对应多个文件，但建议不超过 3 个：
     * 单文件片段数上限会把 Top-K 向少数文件收敛，期望文件越多越可能「检索没错、配额装不下」的假失败。
     */
    private List<String> expectedFiles = new ArrayList<>();
}
