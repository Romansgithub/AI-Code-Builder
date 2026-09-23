package com.quan.aicodehelper.model.dto.app;

import lombok.Data;

import java.io.Serializable;

/**
 * 发起代码生成的请求体。提示词不该散落在 URL 参数里（会被代理、浏览器历史与日志记录），
 * 且长提示词塞进 query 会撞上 URL 长度限制（414）。
 */
@Data
public class GenerateCodeRequest implements Serializable {

    private Long appId;

    private String message;

    private static final long serialVersionUID = 1L;
}
