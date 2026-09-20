package com.quan.aicodehelper.ai.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONObject;
import com.quan.aicodehelper.constant.AppConstant;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Slf4j
@Component
public class FileWriteTool extends BaseTool{
    private final GenerationProgressRegistry generationProgressRegistry;

    public FileWriteTool(GenerationProgressRegistry generationProgressRegistry)
    {
        this.generationProgressRegistry = generationProgressRegistry;
    }

    @Tool("写入文件到指定位置")
    public String writeFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @P("文件内容")
            String content, @ToolMemoryId Long appId){
        try{
            Path path = Paths.get(relativeFilePath);
            if (!path.isAbsolute())
            {
                String projectDirname = CodeGenTypeEnum.VUE_PROJECT.getValue() + "_" + appId;
                Path projectRoot = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirname);
                path = projectRoot.resolve(relativeFilePath);
            }
            Path parentDir = path.getParent();
            if (parentDir != null)
            {
                Files.createDirectories(parentDir);
            }
            Files.write(path,content.getBytes(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            log.info("成功写入文件： {}", path.toAbsolutePath());
            generationProgressRegistry.emit(appId,"项目已生成：" + relativeFilePath);
            return "文件写入成功" + relativeFilePath;
        } catch (IOException e){
            String errorMessage = "文件写入失败" + relativeFilePath + ",错误： " + e.getMessage();
            log.error(errorMessage, e);
            return errorMessage;

        }
    }

    @Override
    public String getToolName() {
        return "WriteFile";
    }

    @Override
    public String getDisplayName() {
        return "写入文件";
    }

    @Override
    public String generateToolExecutedResult(JSONObject argument) {
        String relativeFilePath = argument.getStr("relativeFilePath");
        String suffix = FileUtil.getSuffix(relativeFilePath);
        String content = argument.getStr("content");
        return String.format("[工具调用] %s %s \n```\n%s\n```\n%s\n",getDisplayName(), relativeFilePath, suffix, content);
    }
}
