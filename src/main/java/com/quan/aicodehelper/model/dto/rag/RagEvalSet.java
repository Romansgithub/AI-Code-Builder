package com.quan.aicodehelper.model.dto.rag;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索召回评测集。与代码生成评测集（{@code codegen-eval-set.json}）刻意不合并：那套度量「生成得怎么样」，
 * 这套度量「检索得准不准」；合并会让口径互相污染，生成评测必须隔离掉检索增强（负数 appId）才可比。
 */
@Data
public class RagEvalSet {

    private String name;

    /**
     * 版本号。没有版本就无法跨批次比较指标，故必填。
     */
    private String version;

    /**
     * 评测集说明，加载时打进日志，提醒后来者标注的来源与禁忌。
     */
    private String description;

    private List<RagEvalApp> apps = new ArrayList<>();

    private List<RagEvalCase> cases = new ArrayList<>();
}
