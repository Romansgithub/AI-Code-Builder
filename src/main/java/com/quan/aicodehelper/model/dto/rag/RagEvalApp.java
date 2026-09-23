package com.quan.aicodehelper.model.dto.rag;

import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import lombok.Data;

/**
 * 检索评测的被测应用。
 *
 * <p>这里直接引用真实的产物目录名（{@code {codeGenType}_{appId}}）所对应的 appId，
 * 而不是评测用例自己造一个临时工程 —— 用的是平台上真实生成出来、真实落盘的工程，
 * 检索面对的分布才与线上一致。</p>
 */
@Data
public class RagEvalApp {

    /**
     * 用例里引用这个应用时用的短名。
     */
    private String key;

    /**
     * 中文名称，只用于报告可读性。
     */
    private String label;

    /**
     * 应用 id。产物目录按 {@code {codeGenType}_{appId}} 拼出来，
     * 所以这个值必须与磁盘上的目录名严格对应。
     */
    private long appId;

    /**
     * 代码生成类型，与 appId 一起决定产物目录。
     */
    private CodeGenTypeEnum codeGenType;
}
