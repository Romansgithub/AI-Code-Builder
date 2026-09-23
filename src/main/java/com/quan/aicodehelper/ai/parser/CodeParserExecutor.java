package com.quan.aicodehelper.ai.parser;

import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;

public class CodeParserExecutor {
    private static final HtmlCodeParser htmlCodeParser = new HtmlCodeParser();
    private static final MultiFileCodeParser multiFileCodeParser = new MultiFileCodeParser();

    public static Object executeParser(String codeContent, CodeGenTypeEnum codeGenTypeEnum){
        return switch (codeGenTypeEnum)
        {
            case HTML -> htmlCodeParser.parseCode(codeContent);
            case MULTI_FILE -> multiFileCodeParser.parseCode(codeContent);
            default -> throw new IllegalArgumentException("Unknown code gen type: " + codeGenTypeEnum);
        };

    }
}
