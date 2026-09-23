package com.quan.aicodehelper.ai.parser;

public interface CodeParser<T> {
    T parseCode(String codeContent);
}
