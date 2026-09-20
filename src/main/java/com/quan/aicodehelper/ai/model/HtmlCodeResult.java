package com.quan.aicodehelper.ai.model;

import jdk.jfr.Description;
import lombok.Data;

@Description("生成HTML代码文件的结果")
@Data
public class HtmlCodeResult {
    private String htmlCode;

    private String description;
}
