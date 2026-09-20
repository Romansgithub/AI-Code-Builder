package com.quan.aicodehelper.core.eval;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.quan.aicodehelper.config.EvalProperties;
import com.quan.aicodehelper.exception.BusinessException;
import com.quan.aicodehelper.exception.ErrorCode;
import com.quan.aicodehelper.model.dto.eval.EvalCase;
import com.quan.aicodehelper.model.dto.eval.EvalSet;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 评测集加载器。
 *
 * <p><b>为什么手工解析 JSON 而不是直接 toBean</b>：{@link CodeGenTypeEnum} 的常量名
 * （{@code VUE_PROJECT}）与业务 value（{@code vue_project}）不一致，又没有
 * {@code @JsonValue} 这类提示，Hutool 的自动转换会直接抛
 * {@code ConvertException: Can not convert HTML to ... CodeGenTypeEnum}。
 * 评测集是人工维护的配置，作者写 {@code html} 和写 {@code HTML} 都很自然，两种都得认。
 * 所以这里先转成 {@link JSONObject}，逐字段取值并显式转换，把「怎么解析」握在自己手里。</p>
 *
 * <p><b>为什么校验要严格</b>：评测集是用例的「标准答案」，一旦 id 重复或类型非法，
 * 跑出来的统计数字就是错的 —— 而错误的指标比没有指标更危险，因为它看起来可信。
 * 所以这里宁可抛异常，也不做宽容降级。</p>
 */
@Slf4j
@Component
public class EvalSetLoader {

    @Resource
    private EvalProperties evalProperties;

    /**
     * 加载并校验评测集。
     *
     * @return 评测集
     * @throws BusinessException 资源缺失、JSON 非法或用例校验不通过
     */
    public EvalSet load() {
        String path = evalProperties.getEvalSetPath();
        String content;
        try {
            content = ResourceUtil.readStr(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取评测集失败：" + path);
        }
        if (StrUtil.isBlank(content)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "评测集内容为空：" + path);
        }

        EvalSet evalSet;
        try {
            evalSet = parse(content);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 把异常类型也带上：有些异常（如 NPE）getMessage() 为 null，
            // 只打 message 会得到一句「解析失败：null」，等于没提供任何线索。
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "评测集 JSON 解析失败：" + detail);
        }

        validate(evalSet);
        log.info("已加载评测集「{}」v{}，共 {} 条用例", evalSet.getName(), evalSet.getVersion(),
                evalSet.getCases().size());
        return evalSet;
    }

    /**
     * 解析评测集 JSON。
     *
     * <p>逐字段手工取值，好处是出错时能明确告知「哪条用例的哪个字段有问题」，
     * 而不是抛一句笼统的转换失败。</p>
     */
    private EvalSet parse(String content) {
        JSONObject root = JSONUtil.parseObj(content);
        EvalSet evalSet = new EvalSet();
        evalSet.setName(root.getStr("name"));
        evalSet.setVersion(root.getStr("version"));

        JSONArray caseArray = root.getJSONArray("cases");
        if (caseArray == null) {
            return evalSet;
        }
        for (int i = 0; i < caseArray.size(); i++) {
            JSONObject node = caseArray.getJSONObject(i);
            if (node == null) {
                continue;
            }
            EvalCase c = new EvalCase();
            c.setId(node.getStr("id"));
            c.setDescription(node.getStr("description"));
            c.setPrompt(node.getStr("prompt"));
            c.setCodeGenType(resolveType(node.getStr("codeGenType"), i + 1, c.getId()));
            c.setFaultInjection(resolveFaultInjection(node, i + 1));
            evalSet.getCases().add(c);
        }
        return evalSet;
    }

    /**
     * 解析故障注入配置。
     *
     * <p>没有 {@code faultInjection} 节点时返回 null（正常用例不注入），
     * 但一旦写了这个节点，三个字段就都必须完整 —— 半填的注入配置是配置错误，
     * 而不是「没有注入」。若在这里宽容处理成 null，跑批会静默跳过注入，
     * 得到的「首轮就通过」是假数据，比直接报错危险得多。</p>
     */
    private EvalCase.FaultInjection resolveFaultInjection(JSONObject node, int index) {
        JSONObject fi = node.getJSONObject("faultInjection");
        if (fi == null) {
            return null;
        }
        EvalCase.FaultInjection injection = new EvalCase.FaultInjection();
        // 不用 fi.getStr(...) —— 它内部是 StrUtil.toString，值为 null 时会返回
        // "null" 这个字面量，于是「字段没填」会被当成「填了内容」，缺失检查彻底失效。
        injection.setFile(readText(fi, "file"));
        injection.setFind(readText(fi, "find"));
        injection.setReplace(readText(fi, "replace"));
        // 逐字段判空而不是塞进 Map.entry —— entry 不接受 null，而「字段没填」
        // 恰恰就是 null，用 Map 组装会在判空之前先炸，缺失检查等于没写。
        List<String> missing = new java.util.ArrayList<>();
        if (StrUtil.isBlank(injection.getFile())) {
            missing.add("file");
        }
        if (StrUtil.isBlank(injection.getFind())) {
            missing.add("find");
        }
        if (StrUtil.isBlank(injection.getReplace())) {
            missing.add("replace");
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    String.format("第 %d 条用例的 faultInjection 缺少字段：%s",
                            index, String.join("、", missing)));
        }
        return injection;
    }

    /**
     * 取字符串字段，值为 null 或缺省时返回 null（而不是 "null" 字面量）。
     */
    private static String readText(JSONObject node, String key) {
        Object value = node.get(key);
        return value == null ? null : value.toString();
    }

    /**
     * 把 JSON 里的类型文本解析成枚举。
     *
     * <p>依次尝试：业务 value（{@code vue_project}）→ 常量名（{@code VUE_PROJECT}，
     * 大小写不敏感）。都不匹配则抛错并列出全部合法取值 —— 配置写错的人需要知道
     * 能填什么，而不是只被告知填错了。</p>
     */
    private CodeGenTypeEnum resolveType(String raw, int index, String caseId) {
        if (StrUtil.isBlank(raw)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    String.format("第 %d 条用例%s的 codeGenType 未填写，可选：%s",
                            index, StrUtil.isBlank(caseId) ? "" : "（" + caseId + "）",
                            Arrays.toString(CodeGenTypeEnum.values())));
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
                String.format("用例「%s」的 codeGenType「%s」无法识别，可选：%s",
                        caseId, trimmed, Arrays.toString(CodeGenTypeEnum.values())));
    }

    /**
     * 校验评测集完整性。
     *
     * <p>逐条检查而不是只查总数：报错信息要能直接指到出问题的那条用例，
     * 否则评测集一改大就得靠肉眼找。</p>
     */
    private void validate(EvalSet evalSet) {
        if (evalSet == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "评测集为空");
        }
        if (StrUtil.isBlank(evalSet.getVersion())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "评测集缺少 version —— 没有版本就无法跨批次比较指标");
        }
        var cases = evalSet.getCases();
        if (cases == null || cases.isEmpty()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "评测集不包含任何用例");
        }

        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < cases.size(); i++) {
            EvalCase c = cases.get(i);
            String where = String.format("第 %d 条用例", i + 1);
            if (c == null) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, where + "为空");
            }
            if (StrUtil.isBlank(c.getId())) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, where + "缺少 id");
            }
            // id 会用作产物目录名，必须能安全拼路径
            if (!c.getId().matches("^[A-Za-z0-9_-]+$")) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "用例 id「" + c.getId() + "」只允许字母、数字、下划线和短横线");
            }
            if (!seenIds.add(c.getId())) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "用例 id 重复：" + c.getId());
            }
            if (c.getCodeGenType() == null) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "用例「" + c.getId() + "」的 codeGenType 无法识别，可选："
                                + Arrays.toString(CodeGenTypeEnum.values()));
            }
            if (StrUtil.isBlank(c.getPrompt())) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "用例「" + c.getId() + "」缺少 prompt");
            }
            validateFaultInjection(c);
        }
    }

    /**
     * 校验故障注入的路径安全性。
     *
     * <p>注入是按「项目根 + 相对路径」去改文件的，路径一旦能逃出项目根
     * （{@code ../} 或绝对路径），评测就不再是「破坏被测产物」而变成
     * 「随机改磁盘上的文件」了。这种错必须在校验期拦下，不能等跑批时才炸。</p>
     */
    private void validateFaultInjection(EvalCase c) {
        EvalCase.FaultInjection fi = c.getFaultInjection();
        if (fi == null) {
            return;
        }
        String file = fi.getFile().trim().replace('\\', '/');
        if (file.startsWith("/") || file.matches("^[A-Za-z]:.*")) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "用例「" + c.getId() + "」的 faultInjection.file 必须是相对路径：" + fi.getFile());
        }
        java.nio.file.Path normalized = java.nio.file.Paths.get(file).normalize();
        if (normalized.startsWith("..")) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "用例「" + c.getId() + "」的 faultInjection.file 不允许跳出项目目录：" + fi.getFile());
        }
        if (fi.getFind().equals(fi.getReplace())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "用例「" + c.getId() + "」的 faultInjection 替换前后内容相同，起不到注入效果");
        }
    }
}
