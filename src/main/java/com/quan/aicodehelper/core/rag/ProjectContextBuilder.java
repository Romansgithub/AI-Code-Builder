package com.quan.aicodehelper.core.rag;

import com.quan.aicodehelper.config.RagProperties;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 把检索结果渲染成注入 system message 的项目上下文文本。进 system 而非 user：检索内容体积大
 * 且带注入特征，会踩生成服务的输入护栏；它又是平台自取资料，与「用户敲的话」混在一起是安全模型错配。
 */
@Slf4j
@Service
public class ProjectContextBuilder {

    @Resource
    private RagProperties ragProperties;

    /**
     * 构建注入 system message 的文本。
     *
     * @param slices 检索结果，已按相关度降序
     * @return 注入文本；无可用片段或功能未开启返回空串
     */
    public String build(CodeGenTypeEnum type, List<RetrievedSlice> slices) {
        if (!ragProperties.isEnabled() || slices == null || slices.isEmpty() || type == null) {
            return "";
        }

        String header = buildHeader(type, slices.size());
        String footer = buildFooter();
        int budget = ragProperties.getMaxContextChars();

        StringBuilder body = new StringBuilder();
        int used = header.length() + footer.length();
        int included = 0;
        for (RetrievedSlice slice : slices) {
            String rendered = renderSlice(included + 1, slice);
            if (used + rendered.length() > budget) {
                break;
            }
            body.append(rendered);
            used += rendered.length();
            included++;
        }

        if (included == 0) {
            // 单条就超预算时至少保留一条（截断）：零上下文远差于有一点上下文
            String rendered = renderSlice(1, slices.get(0));
            body.append(truncate(rendered, budget));
            return header + body + footer;
        }

        if (included < slices.size()) {
            body.append("\n（另有 ").append(slices.size() - included)
                    .append(" 个相关片段因上下文长度限制未列出，必要时可用【文件读取工具】按路径自行读取。）\n");
        }
        return header + body + footer;
    }

    private String buildHeader(CodeGenTypeEnum type, int total) {
        return "## 项目现有代码（与本次需求相关的片段）\n\n"
                + "当前工程类型：" + type.getText() + "（" + type.getValue() + "）。\n"
                + "以下 " + total + " 个片段检索自磁盘上工程的当前最新状态，已按与本次需求的相关度排序。\n\n";
    }

    private String buildFooter() {
        return "\n说明：\n"
                + "1. 以上内容是磁盘上的真实代码，比你记忆中的历史版本更可信；"
                + "若两者冲突，一律以上面为准。\n"
                + "2. 上面只是为了展示现有代码，不是输出格式示例 —— "
                + "你的输出仍须严格遵守本消息开头的格式要求。\n"
                + "3. 需要查看未列出文件的内容时，使用【文件读取工具】按路径读取。\n";
    }

    private String renderSlice(int order, RetrievedSlice slice) {
        String language = fenceLanguage(slice.filePath());
        return "### " + order + ". " + slice.locationLabel() + "\n"
                + "```" + language + "\n"
                + slice.content() + "\n"
                + "```\n\n";
    }

    private String truncate(String text, int budget) {
        if (budget <= 0 || text.length() <= budget) {
            return text;
        }
        int keep = Math.max(1, budget - "\n…（片段已截断）\n".length());
        return text.substring(0, keep) + "\n…（片段已截断）\n";
    }

    /** 代码块语言标记；标错会让模型误读语法，认不出时留空。 */
    private static String fenceLanguage(String filePath) {
        if (filePath == null) {
            return "";
        }
        int dot = filePath.lastIndexOf('.');
        if (dot < 0 || dot == filePath.length() - 1) {
            return "";
        }
        return switch (filePath.substring(dot + 1).toLowerCase()) {
            case "vue" -> "vue";
            case "ts" -> "typescript";
            case "tsx" -> "tsx";
            case "js", "mjs", "cjs" -> "javascript";
            case "jsx" -> "jsx";
            case "html", "htm" -> "html";
            case "css" -> "css";
            case "scss" -> "scss";
            case "less" -> "less";
            case "json" -> "json";
            case "md" -> "markdown";
            case "yml", "yaml" -> "yaml";
            case "sql" -> "sql";
            case "xml", "svg" -> "xml";
            case "java" -> "java";
            case "py" -> "python";
            case "sh" -> "bash";
            default -> "";
        };
    }
}
