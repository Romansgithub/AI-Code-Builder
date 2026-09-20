package com.quan.aicodehelper.ai.saver;

import com.quan.aicodehelper.ai.model.HtmlCodeResult;
import com.quan.aicodehelper.ai.model.MultiFileCodeResult;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;

import java.io.File;

public class CodeFileSaverExecutor {
    private static final HtmlCodeFileSaverTemplate htmlCodeFileSaverTemplate = new HtmlCodeFileSaverTemplate();

    private static final MultiCodeFileSaverTemplate multiCodeFileSaverTemplate = new MultiCodeFileSaverTemplate();

    public static File executeSaver(Object codeResult, CodeGenTypeEnum codeGenTypeEnum, Long appId)
    {
        return switch (codeGenTypeEnum)
        {
            case HTML -> htmlCodeFileSaverTemplate.saveCode((HtmlCodeResult) codeResult, appId);
            case MULTI_FILE -> multiCodeFileSaverTemplate.saveCode((MultiFileCodeResult) codeResult, appId);
            default -> throw new IllegalArgumentException("不支持的代码生成类型: " + codeGenTypeEnum);
        };
    }

}
