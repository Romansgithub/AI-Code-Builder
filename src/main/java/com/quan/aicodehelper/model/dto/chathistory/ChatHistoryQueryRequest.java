package com.quan.aicodehelper.model.dto.chathistory;

import com.quan.aicodehelper.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;
//import java.time.LocalDateTime;

@EqualsAndHashCode(callSuper = true)
@Data
public class ChatHistoryQueryRequest extends PageRequest implements Serializable {
    private Long id;
    private String message;
    private String messageType;
    private Long appId;
    private Long userId;
    private LocalDateTime lastCreateTime;
    private static final long serialVersionUID = 1L;
}
