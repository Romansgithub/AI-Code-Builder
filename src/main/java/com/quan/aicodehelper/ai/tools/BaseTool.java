package com.quan.aicodehelper.ai.tools;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONParser;
import org.springframework.stereotype.Component;
import org.checkerframework.checker.units.qual.C;

@Component
public abstract class BaseTool {
    /**
     * 获取工具名称
     * @return
     */
    public abstract String getToolName();

    /**
     * 获取工具的中文显示名称
     * @return
     */
    public abstract String getDisplayName();

    /**
     * 生成工具请求时的返回值（显示给用户）
     * @return
     */
    public String generateToolRequestResponse() {
        return String.format("\n\n[选择工具] %s\n\n", getDisplayName());
    }

    /**
     * 生成工具执行后的返回值（显示给用户）
     * @param argument
     * @return
     */
    public abstract String generateToolExecutedResult(JSONObject argument);
}
