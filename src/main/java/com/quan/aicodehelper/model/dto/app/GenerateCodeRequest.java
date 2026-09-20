package com.quan.aicodehelper.model.dto.app;

import lombok.Data;

import java.io.Serializable;

/**
 * 发起代码生成的请求体。
 *
 * <p>它存在的意义不只是「把参数从 URL 挪到 body」：URL 参数会被代理、
 * 浏览器历史与日志完整记录，而用户输入的提示词不该散落在这些地方；
 * 更实际的是长度 —— 长提示词塞进 query 会撞上 URL 长度限制（414）。</p>
 */
@Data
public class GenerateCodeRequest implements Serializable {

    /**
     * 应用 ID。
     */
    private Long appId;

    /**
     * 用户消息（即生成需求）。
     */
    private String message;

    private static final long serialVersionUID = 1L;
}
