package com.quan.aicodehelper.ai.service;

import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import dev.langchain4j.service.SystemMessage;
import org.springframework.stereotype.Service;

@Service
public interface AiCodeGenTypeRoutingService {

    @SystemMessage(fromResource = "prompt/codegen-routing-system-prompt.txt")
    CodeGenTypeEnum routeCodeGenType(String userPrompt);
}
