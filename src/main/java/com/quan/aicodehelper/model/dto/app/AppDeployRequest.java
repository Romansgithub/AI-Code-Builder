package com.quan.aicodehelper.model.dto.app;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AppDeployRequest implements Serializable {
    private Long appId;

    private static final long serialVersionUID = 1L;
}
