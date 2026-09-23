package com.quan.aicodehelper.model.dto.app;

import lombok.Data;

import java.io.Serializable;

@Data
public class AppUpdateRequest implements Serializable {

    private Long id;

    private String appName;

    private static final long serialVersionUID = 1L;
}
