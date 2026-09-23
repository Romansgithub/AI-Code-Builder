package com.quan.aicodehelper.model.dto.app;

import lombok.Data;

import java.io.Serializable;

@Data
public class AppAddRequest implements Serializable {

    private String appName;

    private String initPrompt;

    private String codeGenType;

    private static final long serialVersionUID = 1L;
}
