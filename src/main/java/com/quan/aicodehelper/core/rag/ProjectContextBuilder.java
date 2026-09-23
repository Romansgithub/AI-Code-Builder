package com.quan.aicodehelper.core.rag;

import com.quan.aicodehelper.config.RagProperties;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 把检索结果渲染成「注入给模型的项目上下文」文本。
 *
 * <p><b>这段文本会进 system message，不是 userMessage</b>。
 * 原因是生成服务上挂着输入护栏（{@code PromptSafetyInputGuardrail}），
 * 它管两件事：用户输入不超过 1000 字、且不含提示词注入特征。
 * 检索内容两项都会踩雷 ——</p>
 * <ul>
 *   <li>几 KB 的代码，长度上必然超限；</li>
 *   <li>代码里出现 {@code systemPrompt: "You are ..."} 这类字面量时，
 *       会被注入模式误判成攻击，把整个请求拒掉 ——
 *       而且这种失败是「看起来像内容不合规」的，很难联想到是检索引入的。</li>
 * </ul>
 * <p>更根本的是语义：护栏的对象是「用户在聊天框里敲的话」，
 * 而检索片段是平台自己从磁盘读来的资料。把两者混在一起，
 * 既是安全模型上的错配，也必然带来这组长度与模式的冲突。
 * 这和构建自愈当年踩的坑是同一个（见 {@code AiCodeHealingService} 的类注释）。</p>
 */
@Slf4j
@Service
public class ProjectContextBuilder {

    @Resource
    private RagProperties ragProperties;

    /**
     * 构建注入文本。
     *
     * @param type   当前工程的生成类型
     * @param slices 检索结果（已按相关度降序）
     * @return 注入 system message 的文本；无可用片段时返回空串
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
            // 单条片段就超预算时，至少保留一条（截断）——
            // 零上下文与「有一点上下文」的差别，远大于「完整片段」与「截断片段」的差别
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

    /**
     * 代码块的语言标记。标错会让部分模型把内容当成另一种语法解读，
     * 认不出来时宁可留空。
     */
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
