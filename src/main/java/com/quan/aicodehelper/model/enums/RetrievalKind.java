package com.quan.aicodehelper.model.enums;

import lombok.Getter;

/**
 * 检索评测用例的类型。
 *
 * <p><b>为什么必须分这两类统计</b>：混合检索的得分里有一项「需求直接点名了文件名」
 * 的加成，关键词那一路也能靠文件名与标识符命中。若把两类查询混在一起算平均召回，
 * 点名型会一路拿满分，把语义型的短板完全掩盖掉 —— 于是得出「关键词已经够用、
 * 向量没有价值」这种与事实相反的结论。分开统计才能看清向量到底补了多少。</p>
 */
@Getter
public enum RetrievalKind {

    /**
     * 点名型：需求里直接写了文件名或类名（「把 Navbar.vue 的导航项改掉」）。
     * 这是最理想也最不真实的情况 —— 真实用户很少知道文件叫什么。
     */
    POINTED("点名型", "需求里直接写出了文件名或类名"),

    /**
     * 语义型：需求只描述功能与位置，不含任何代码标识符
     * （「页面向下滚动之后顶上那条导航栏的背景要变白」）。
     * 这才是用户真实的表达方式，也是向量检索存在意义的所在。
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
