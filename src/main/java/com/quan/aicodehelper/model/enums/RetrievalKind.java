package com.quan.aicodehelper.model.enums;

import lombok.Getter;

/**
 * 检索评测用例的类型。必须分两类统计：点名型会被文件名加成拿满分，与语义型混算平均召回会掩盖语义短板，得出「向量无价值」的相反结论。
 */
@Getter
public enum RetrievalKind {

    /**
     * 点名型：需求里直接写了文件名或类名。真实用户很少知道文件叫什么。
     */
    POINTED("点名型", "需求里直接写出了文件名或类名"),

    /**
     * 语义型：需求只描述功能与位置，不含代码标识符。这才是用户真实表达，也是向量检索存在意义。
     */
    SEMANTIC("语义型", "需求只描述功能，不含任何代码标识符");

    private final String text;

    /**
     * 这一类用例考验的是什么，输出报告时一并给出，避免看数的人误读。
     */
    private final String hint;

    RetrievalKind(String text, String hint) {
        this.text = text;
        this.hint = hint;
    }
}
