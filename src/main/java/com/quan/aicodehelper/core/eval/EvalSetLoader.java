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
 * 评测集加载器。手工解析 JSON 而非直接 toBean：{@link CodeGenTypeEnum} 的常量名
 * （{@code VUE_PROJECT}）与业务 value（{@code vue_project}）不一致，Hutool 自动转换会直接抛
 * {@code ConvertException}，而评测集是人工维护的，{@code html} 与 {@code HTML} 两种写法都得认。
 *
 * <p>校验必须严格：id 重复或类型非法都会让统计数字变成错的，而错误的指标看起来可信、比没有更危险。</p>
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
            // 带上异常类型：有些异常（如 NPE）getMessage() 为 null，只打 message 会得到「解析失败：null」。
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "评测集 JSON 解析失败：" + detail);
        }

        validate(evalSet);
        log.info("已加载评测集「{}」v{}，共 {} 条用例", evalSet.getName(), evalSet.getVersion(),
                evalSet.getCases().size());
        return evalSet;
    }

    /**
     * 解析评测集 JSON。逐字段手工取值，出错时能明确告知「哪条用例的哪个字段有问题」，
     * 而不是抛一句笼统的转换失败。
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
     * 解析故障注入配置。没有 {@code faultInjection} 节点时返回 null（正常用例不注入）；
     * 一旦写了这个节点，{@code find} 与 {@code replace} 必须完整，少了就是配置错误而非「没有注入」，
     * 宽容处理会让跑批静默跳过注入、得到「首轮就通过」的假数据。
     *
     * <p>{@code file} 可留空表示自动定位，这是更稳的用法：模型把组件 import 写在哪个文件并不稳定，
     * 写死文件会在模型换一种组织方式时失效。</p>
     */
    private EvalCase.FaultInjection resolveFaultInjection(JSONObject node, int index) {
        JSONObject fi = node.getJSONObject("faultInjection");
        if (fi == null) {
            return null;
        }
        EvalCase.FaultInjection injection = new EvalCase.FaultInjection();
        // 不用 fi.getStr(...)：它内部是 StrUtil.toString，值为 null 时返回 "null" 字面量，
        // 于是「字段没填」被当成「填了内容」，缺失检查彻底失效。
        injection.setFile(readText(fi, "file"));
        injection.setFind(readText(fi, "find"));
        injection.setReplace(readText(fi, "replace"));
        // 逐字段判空而非塞进 Map.entry：entry 不接受 null，而「字段没填」就是 null，
        // 用 Map 组装会在判空之前先炸。file 不在此列，它可留空（留空即自动定位）。
        List<String> missing = new java.util.ArrayList<>();
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
     * 把 JSON 里的类型文本解析成枚举：先按业务 value（{@code vue_project}），再按常量名
     * （{@code VUE_PROJECT}，大小写不敏感）。都不匹配则抛错并列出全部合法取值。
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
     * 校验评测集完整性。逐条检查而非只查总数：报错要能直接指到出问题的那条用例。
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
     * 校验故障注入的路径安全性。注入按「项目根 + 相对路径」改文件，路径一旦能逃出项目根
     * （{@code ../} 或绝对路径），评测就从「破坏被测产物」变成「随机改磁盘上的文件」，
     * 必须在校验期拦下。{@code file} 留空合法，此时不校验路径。
     */
    private void validateFaultInjection(EvalCase c) {
        EvalCase.FaultInjection fi = c.getFaultInjection();
        if (fi == null) {
            return;
        }
        if (StrUtil.isNotBlank(fi.getFile())) {
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
        }
        if (fi.getFind().equals(fi.getReplace())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "用例「" + c.getId() + "」的 faultInjection 替换前后内容相同，起不到注入效果");
        }
    }
}
