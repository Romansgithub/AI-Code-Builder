package com.quan.aicodehelper.model.dto.rag;

import com.quan.aicodehelper.model.enums.RetrievalKind;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条检索评测用例。
 *
 * <p><b>expectedFiles 是人工标注的「标准答案」</b>：需求落到工程上应该改哪个文件，
 * 由人读源码后判定。绝不能用模型先猜一份答案、再拿它去评测模型自己的检索 ——
 * 那样测的是「模型与自己的自洽程度」，与检索质量无关，而且看起来还很漂亮。</p>
 */
@Data
public class RagEvalCase {

    /**
     * 用例标识，报告里按它定位到具体是哪条。
     */
    private String id;

    /**
     * 引用 {@link RagEvalApp#getKey()} 指明被测应用。
     */
    private String appKey;

    /**
     * 用例类型，决定它被归入哪个统计分组。
     */
    private RetrievalKind kind;

    /**
     * 用户的修改需求原文，直接作为检索 query。
     */
    private String query;

    /**
     * 期望命中的文件（相对工程根的路径，正斜杠）。
     *
     * <p>允许一条需求对应多个文件，但**不建议超过 3 个** ——
     * 单文件片段数上限（{@code max-chunks-per-file}）会把 Top-K 向少数文件收敛，
     * 期望文件越多，越可能出现「检索本身没错，但配额装不下」的假失败。</p>
     */
    private List<String> expectedFiles = new ArrayList<>();
}
