package com.quan.aicodehelper.core.rag.eval;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.quan.aicodehelper.config.RagProperties;
import com.quan.aicodehelper.exception.BusinessException;
import com.quan.aicodehelper.exception.ErrorCode;
import com.quan.aicodehelper.model.dto.rag.RagEvalApp;
import com.quan.aicodehelper.model.dto.rag.RagEvalCase;
import com.quan.aicodehelper.model.dto.rag.RagEvalSet;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import com.quan.aicodehelper.model.enums.RetrievalKind;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检索评测集加载器。手工解析而非 toBean（枚举常量名与业务 value 不一致，且人工维护的写法大小写都得认）；校验严格：配置错会让指标静默变 0，比没有指标更危险，故宁可抛异常。
 */
@Slf4j
@Component
public class RagEvalSetLoader {

    private static final Pattern FILE_CLUE = Pattern.compile(
            "\\.(vue|js|css|html|json)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern IDENTIFIER_CLUE = Pattern.compile("[a-z]+[A-Z][A-Za-z]*");

    @Resource
    private RagProperties ragProperties;

    /** 加载并校验检索评测集。@throws BusinessException 资源缺失、JSON 非法或校验不通过 */
    public RagEvalSet load() {
        String path = ragProperties.getEvalSetPath();
        String content;
        try {
            content = ResourceUtil.readStr(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取检索评测集失败：" + path);
        }
        if (StrUtil.isBlank(content)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "检索评测集内容为空：" + path);
        }

        RagEvalSet evalSet;
        try {
            evalSet = parse(content);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "检索评测集 JSON 解析失败：" + detail);
        }

        validate(evalSet);
        log.info("已加载检索评测集「{}」v{}：{} 个应用，{} 条用例",
                evalSet.getName(), evalSet.getVersion(),
                evalSet.getApps().size(), evalSet.getCases().size());
        return evalSet;
    }

    /** 解析评测集 JSON。包可见以便单测覆盖各校验分支（防止指标静默变 0 的唯一防线）。 */
    RagEvalSet parse(String content) {
        JSONObject root = JSONUtil.parseObj(content);
        RagEvalSet evalSet = new RagEvalSet();
        evalSet.setName(root.getStr("name"));
        evalSet.setVersion(root.getStr("version"));
        evalSet.setDescription(root.getStr("description"));

        JSONArray appArray = root.getJSONArray("apps");
        if (appArray != null) {
            for (int i = 0; i < appArray.size(); i++) {
                JSONObject node = appArray.getJSONObject(i);
                if (node == null) {
                    continue;
                }
                RagEvalApp app = new RagEvalApp();
                app.setKey(node.getStr("key"));
                app.setLabel(node.getStr("label"));
                Long appId = node.getLong("appId");
                app.setAppId(appId == null ? 0L : appId);
                app.setCodeGenType(resolveType(node.getStr("codeGenType"), "第 " + (i + 1) + " 个应用"));
                evalSet.getApps().add(app);
            }
        }

        JSONArray caseArray = root.getJSONArray("cases");
        if (caseArray != null) {
            for (int i = 0; i < caseArray.size(); i++) {
                JSONObject node = caseArray.getJSONObject(i);
                if (node == null) {
                    continue;
                }
                RagEvalCase c = new RagEvalCase();
                c.setId(node.getStr("id"));
                c.setAppKey(node.getStr("appKey"));
                c.setKind(resolveKind(node.getStr("kind"), i + 1, c.getId()));
                c.setQuery(node.getStr("query"));
                c.setExpectedFiles(readFiles(node, i + 1, c.getId()));
                evalSet.getCases().add(c);
            }
        }
        return evalSet;
    }

    /** 读取期望文件清单并统一为「正斜杠 + 相对工程根」。否则标注写成 src\... 会全部匹配不上，误判检索失效。 */
    private List<String> readFiles(JSONObject node, int index, String caseId) {
        JSONArray array = node.getJSONArray("expectedFiles");
        List<String> files = new java.util.ArrayList<>();
        if (array == null) {
            return files;
        }
        for (int i = 0; i < array.size(); i++) {
            String raw = array.getStr(i);
            if (StrUtil.isBlank(raw)) {
                continue;
            }
            files.add(raw.trim().replace('\\', '/'));
        }
        return files;
    }

    private RetrievalKind resolveKind(String raw, int index, String caseId) {
        if (StrUtil.isBlank(raw)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    String.format("第 %d 条用例%s的 kind 未填写，可选：%s",
                            index, StrUtil.isBlank(caseId) ? "" : "（" + caseId + "）",
                            Arrays.toString(RetrievalKind.values())));
        }
        String trimmed = raw.trim();
        for (RetrievalKind k : RetrievalKind.values()) {
            if (k.name().equalsIgnoreCase(trimmed) || k.getText().equals(trimmed)) {
                return k;
            }
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                String.format("用例「%s」的 kind「%s」无法识别，可选：%s",
                        caseId, trimmed, Arrays.toString(RetrievalKind.values())));
    }

    private CodeGenTypeEnum resolveType(String raw, String where) {
        if (StrUtil.isBlank(raw)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    where + "的 codeGenType 未填写，可选：" + Arrays.toString(CodeGenTypeEnum.values()));
        }
        String trimmed = raw.trim();
        CodeGenTypeEnum byValue = CodeGenTypeEnum.getEnumByValue(trimmed);
        if (byValue != null) {
            return byValue;
        }
        for (CodeGenTypeEnum e : CodeGenTypeEnum.values()) {
            if (e.name().equalsIgnoreCase(trimmed)) {
                return e;
            }
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                where + "的 codeGenType「" + trimmed + "」无法识别，可选："
                        + Arrays.toString(CodeGenTypeEnum.values()));
    }

    /** 校验评测集完整性。包可见以便单测覆盖各校验分支。 */
    void validate(RagEvalSet evalSet) {
        if (StrUtil.isBlank(evalSet.getVersion())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "检索评测集缺少 version —— 没有版本就无法跨批次比较指标");
        }
        validateApps(evalSet);
        validateCases(evalSet);
    }

    private void validateApps(RagEvalSet evalSet) {
        if (evalSet.getApps().isEmpty()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "检索评测集未定义任何应用");
        }
        Set<String> keys = new HashSet<>();
        for (RagEvalApp app : evalSet.getApps()) {
            if (StrUtil.isBlank(app.getKey())) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "存在 key 为空的评测应用");
            }
            if (!keys.add(app.getKey())) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "评测应用 key 重复：" + app.getKey());
            }
            // 负数 appId 是评测会话约定，检索器直接返回空，写进来会得到「召回率 0%」假结论，须在配置期拦下
            if (app.getAppId() <= 0) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "应用「" + app.getKey() + "」的 appId 必须为正数（当前 "
                                + app.getAppId() + "）—— 非正数会让检索直接返回空，"
                                + "跑出来是「召回率 0%」而不是报错");
            }
            if (app.getCodeGenType() == null) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "应用「" + app.getKey() + "」的 codeGenType 无法识别");
            }
        }
    }

    private void validateCases(RagEvalSet evalSet) {
        if (evalSet.getCases().isEmpty()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "检索评测集不包含任何用例");
        }
        Set<String> appKeys = new HashSet<>();
        for (RagEvalApp app : evalSet.getApps()) {
            appKeys.add(app.getKey());
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < evalSet.getCases().size(); i++) {
            RagEvalCase c = evalSet.getCases().get(i);
            String where = "第 " + (i + 1) + " 条用例";
            if (c == null) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, where + "为空");
            }
            if (StrUtil.isBlank(c.getId())) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, where + "缺少 id");
            }
            if (!c.getId().matches("^[A-Za-z0-9_-]+$")) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "用例 id「" + c.getId() + "」只允许字母、数字、下划线和短横线");
            }
            if (!ids.add(c.getId())) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "用例 id 重复：" + c.getId());
            }
            if (StrUtil.isBlank(c.getQuery())) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "用例「" + c.getId() + "」缺少 query");
            }
            if (c.getKind() == null) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "用例「" + c.getId() + "」的 kind 无法识别");
            }
            // appKey 写错会让用例静默跑在空应用上，表现为「怎么检索都命中不了」
            if (!appKeys.contains(c.getAppKey())) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "用例「" + c.getId() + "」引用了不存在的 appKey：" + c.getAppKey()
                                + "，已定义的有 " + appKeys);
            }
            // 语义型混进代码标识符会退化成关键词题却仍计入语义型分母，指标失真毫无痕迹
            if (c.getKind() == RetrievalKind.SEMANTIC) {
                validateSemanticHasNoCodeClue(c);
            }
            validateExpectedFiles(c);
        }
    }

    /**
     * 校验语义型用例 query 不含代码线索：点名文件或标识符后关键词侧即命中，用例不再是语义题却仍进分母，抬高召回、抹平向量收益。
     */
    private void validateSemanticHasNoCodeClue(RagEvalCase c) {
        Matcher file = FILE_CLUE.matcher(c.getQuery());
        if (file.find()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "语义型用例「" + c.getId() + "」的 query 里出现了文件名线索「" + file.group()
                            + "」—— 语义型必须只描述功能，不能点名文件，"
                            + "否则它会被关键词命中却仍计入语义型，把向量的收益算没");
        }
        Matcher identifier = IDENTIFIER_CLUE.matcher(c.getQuery());
        if (identifier.find()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "语义型用例「" + c.getId() + "」的 query 里出现了代码标识符「" + identifier.group()
                            + "」—— 语义型描述的是用户视角的需求，不该出现代码里的命名，"
                            + "否则这条用例不再是语义题");
        }
    }

    /** 校验期望文件路径：只许相对路径且不得跳出工程根，否则恒不等于检索结果相对路径而误判漏召。 */
    private void validateExpectedFiles(RagEvalCase c) {
        if (c.getExpectedFiles().isEmpty()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "用例「" + c.getId() + "」没有标注 expectedFiles —— 没有标准答案就无法计算召回率");
        }
        for (String file : c.getExpectedFiles()) {
            if (file.startsWith("/") || file.matches("^[A-Za-z]:.*")) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "用例「" + c.getId() + "」的期望文件必须是相对路径：" + file);
            }
            if (Paths.get(file).normalize().startsWith("..")) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "用例「" + c.getId() + "」的期望文件不允许跳出工程目录：" + file);
            }
        }
    }
}
