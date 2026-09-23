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
 * 检索评测集加载器。
 *
 * <p><b>为什么手工解析而不是 toBean</b>：与 {@link com.quan.aicodehelper.core.eval.EvalSetLoader}
 * 同样的原因 —— {@link CodeGenTypeEnum} 的常量名与业务 value 不一致，
 * 自动转换会抛 {@code ConvertException}；而且评测集是人工维护的，
 * 作者写 {@code vue_project} 和写 {@code VUE_PROJECT} 都很自然，两种都得认。</p>
 *
 * <p><b>为什么校验必须严格</b>：这个评测集的产物是一串「召回率」数字，
 * 会被写进文档与答辩材料。配置错误在这里造成的后果是<b>指标静默变 0</b> ——
 * 例如 appId 误填成负数时，检索器会按「评测会话」快速返回空，
 * 跑出来是「召回率 0%」，看起来像「检索做得很差」而不是「配置填错了」。
 * 错误的指标比没有指标更危险，因为它看起来可信。所以宁可抛异常。</p>
 */
@Slf4j
@Component
public class RagEvalSetLoader {

    /**
     * 语义型用例里不允许出现的「文件名 / 扩展名」线索。
     */
    private static final Pattern FILE_CLUE = Pattern.compile(
            "\\.(vue|js|css|html|json)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 语义型用例里不允许出现的驼峰标识符（如 {@code localStorage}、{@code Navbar}）。
     */
    private static final Pattern IDENTIFIER_CLUE = Pattern.compile("[a-z]+[A-Z][A-Za-z]*");

    @Resource
    private RagProperties ragProperties;

    /**
     * 加载并校验检索评测集。
     *
     * @return 评测集
     * @throws BusinessException 资源缺失、JSON 非法或校验不通过
     */
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

    /**
     * 解析评测集 JSON。
     *
     * <p>包可见而非 private：校验规则是「防止指标静默变 0」的唯一防线，
     * 必须能针对非法配置逐一测试。只测真实那一份评测集是测不出边界的。</p>
     */
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

    /**
     * 读取期望文件清单，并统一成「正斜杠 + 相对工程根」的写法。
     *
     * <p>归一化是必须的：检索结果里的 filePath 一律是正斜杠相对路径，
     * 标注时手滑写成 {@code src\components\Navbar.vue} 就会全部匹配不上，
     * 表现为「检索一条都没命中」，而实际上检索是对的。</p>
     */
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

    /**
     * 校验评测集完整性。包可见以便单测覆盖各条校验分支。
     */
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
            // 负数 appId 是评测会话的约定，检索器会直接返回空。
            // 写进来只会得到「召回率 0%」这种看起来像检索很差的假结论，必须在配置期拦下。
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
            // appKey 写错会让这条用例静默跑在空应用上，最终表现为「怎么检索都命中不了」
            if (!appKeys.contains(c.getAppKey())) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "用例「" + c.getId() + "」引用了不存在的 appKey：" + c.getAppKey()
                                + "，已定义的有 " + appKeys);
            }
            // 语义型混进代码标识符，这条用例就退化成关键词题，
            // 却仍然被算在语义型的分母里 —— 指标失真得毫无痕迹
            if (c.getKind() == RetrievalKind.SEMANTIC) {
                validateSemanticHasNoCodeClue(c);
            }
            validateExpectedFiles(c);
        }
    }

    /**
     * 校验语义型用例的 query 里没有代码线索。
     *
     * <p><b>为什么必须拦</b>：语义型这一组的全部意义，是度量「需求里没有任何代码标识符时
     * 检索还能不能找对文件」。一旦 query 里写了 {@code Navbar.vue} 或 {@code localStorage}，
     * 关键词侧立刻就能命中，这条用例便不再是语义题 —— 但它照样进语义型的分母，
     * 于是语义型的召回率被抬高、向量的增量收益被抹平，而报告里看不出任何异常。
     * 这比配置写错更隐蔽：配置错会让指标变成 0，谁都看得见；这个是让指标悄悄变好。</p>
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

    /**
     * 校验期望文件路径。
     *
     * <p>只允许相对路径且不得跳出工程根：这个字段是拿来做字符串比对的，
     * 一个 {@code ../} 或盘符路径永远不会等于检索结果里的相对路径，
     * 于是该用例恒不命中，而看报告的人只会以为检索漏了它。</p>
     */
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
