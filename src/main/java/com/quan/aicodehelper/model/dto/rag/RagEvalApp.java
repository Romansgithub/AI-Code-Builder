package com.quan.aicodehelper.model.dto.rag;

import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import lombok.Data;

/**
 * 检索评测的被测应用。直接引用真实产物目录（{@code {codeGenType}_{appId}}）对应的 appId，
 * 而不是评测用例自造临时工程：用平台上真实生成、真实落盘的工程，检索面对的分布才与线上一致。
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
     * 应用 id。产物目录按 {@code {codeGenType}_{appId}} 拼出，必须与磁盘上的目录名严格对应。
     */
    private long appId;

    /**
     * 代码生成类型，与 appId 一起决定产物目录。
     */
    private CodeGenTypeEnum codeGenType;
}
