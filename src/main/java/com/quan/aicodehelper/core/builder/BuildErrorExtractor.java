package com.quan.aicodehelper.core.builder;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 构建错误提取器。
 *
 * <p>npm / vite 的输出中大部分是噪音：{@code npm notice}、进度条、deprecation 警告、
 * 包体积提示等。全部塞进提示词既浪费 token 又干扰模型判断。
 * 本类负责从中挑出真正有诊断价值的行，并做长度截断。</p>
 *
 * <p>提取策略（按优先级）：
 * <ol>
 *   <li>带 {@code error} 关键字的行（覆盖 tsc / vite / rollup / esbuild 的报错格式）</li>
 *   <li>带「文件路径 + 行号:列号」的行（如 {@code src/App.vue:12:5}），这是最能定位问题的信息</li>
 *   <li>vite 特有的 {@code X [ERROR]} / {@code failed to load} / {@code Could not resolve} 等</li>
 * </ol>
 */
public final class BuildErrorExtractor {

    /**
     * 单次提取的默认字符上限。超过则截断，避免提示词过长导致 token 爆炸。
     */
    public static final int DEFAULT_MAX_LENGTH = 3000;

    /**
     * 明确要过滤掉的噪音行特征（前缀匹配，不区分大小写）。
     *
     * <p>只做前缀匹配而非包含匹配：像 {@code npm error} 这类真正的报错
     * 不能被误伤，因此前缀必须足够明确。</p>
     */
    private static final String[] NOISE_PREFIXES = {
            "npm notice",
            "npm warn",
            "added ",
            "removed ",
            "audited ",
            "changed ",
            "up to date",
            "packages are looking for funding",
            "run `npm fund",
            "vite v",
            "building for production",
            "transforming",
            "rendering chunks",
            "computing gzip size",
            "> ",
            "$ ",
            "✓ built in",
            "dist/",
            "gzip:",
    };

    /**
     * 包含即视为噪音的子串（如「已弃用」提示）。
     */
    private static final String[] NOISE_CONTAINS = {
            "deprecated",
            "found 0 vulnerabilities",
    };

    /**
     * 判定为「值得保留」的错误特征。
     */
    private static final String[] ERROR_MARKERS = {
            "error",
            "err!",
            "failed",
            "cannot find",
            "could not resolve",
            "unexpected",
            "unterminated",
            "syntaxerror",
            "typeerror",
            "referenceerror",
            "is not defined",
            "does not exist",
            "missing",
            "invalid",
            "unexpected token",
            "expected",
            "duplicate",
            "not found",
    };

    private BuildErrorExtractor() {
    }

    /**
     * 从构建输出中提取错误信息。
     *
     * @param rawOutput 原始输出（stdout + stderr 合并）
     * @return 提取后的错误文本；若未识别出任何错误行，则退化为「原输出尾部截断」
     */
    public static String extract(String rawOutput) {
        return extract(rawOutput, DEFAULT_MAX_LENGTH);
    }

    /**
     * 从构建输出中提取错误信息，并限制长度。
     *
     * @param rawOutput  原始输出
     * @param maxLength  结果字符上限
     */
    public static String extract(String rawOutput, int maxLength) {
        if (StrUtil.isBlank(rawOutput)) {
            return "";
        }
        String[] lines = rawOutput.split("\\R");
        // 用 LinkedHashSet 去重并保持出现顺序 —— 重复的报错（如多个文件同一错误）只留一条
        Set<String> picked = new LinkedHashSet<>();

        for (int i = 0; i < lines.length; i++) {
            String line = cleanLine(lines[i]);
            if (line.isEmpty() || isNoise(line)) {
                continue;
            }
            String lower = line.toLowerCase();
            boolean isError = containsAny(lower, ERROR_MARKERS);
            boolean hasLocation = hasFileLocation(line);
            if (isError || hasLocation) {
                // 带上下一行作为上下文：vite 常把「出错文件」与「具体原因」分成两行
                picked.add(line);
                if (i + 1 < lines.length) {
                    String next = cleanLine(lines[i + 1]);
                    if (!next.isEmpty() && !isNoise(next)) {
                        picked.add(next);
                    }
                }
            }
        }

        String result;
        if (picked.isEmpty()) {
            // 没有识别出错误行。此时不能再退回去返回原始输出 —— 那等于把刚刚过滤掉的
            // npm notice / vite 横幅原样放回来，白白污染提示词。
            // 改为：在「已过滤噪音」的行里取尾部（真正的报错往往在最后），
            // 若连非噪音行都没有，则返回空串，交由调用方走「未能提取到具体错误信息」分支。
            result = tailOfNonNoiseLines(lines, maxLength);
        } else {
            result = String.join("\n", picked);
        }
        return truncate(result, maxLength);
    }

    /**
     * 取「非噪音行」的尾部若干行。
     *
     * <p>兜底策略：不认得错误特征时，最后几行往往最接近真实原因。</p>
     */
    private static String tailOfNonNoiseLines(String[] lines, int maxLength) {
        List<String> meaningful = new ArrayList<>();
        for (String line : lines) {
            String cleaned = cleanLine(line);
            if (!cleaned.isEmpty() && !isNoise(cleaned)) {
                meaningful.add(cleaned);
            }
        }
        if (meaningful.isEmpty()) {
            return "";
        }
        // 从尾部累积，直到接近长度上限
        List<String> chosen = new ArrayList<>();
        int length = 0;
        for (int i = meaningful.size() - 1; i >= 0; i--) {
            String line = meaningful.get(i);
            if (length + line.length() + 1 > maxLength && !chosen.isEmpty()) {
                break;
            }
            chosen.add(0, line);
            length += line.length() + 1;
        }
        return String.join("\n", chosen);
    }

    /**
     * 去掉 ANSI 颜色码与首尾空白。
     */
    private static String cleanLine(String line) {
        if (line == null) {
            return "";
        }
        // 去掉 ANSI 转义序列（vite/rollup 的输出带颜色码，直接进提示词会干扰模型）
        String cleaned = line.replaceAll("\\u001B\\[[\\d;]*[A-Za-z]", "");
        return cleaned.trim();
    }

    private static boolean isNoise(String line) {
        String lower = line.toLowerCase();
        for (String prefix : NOISE_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        for (String contains : NOISE_CONTAINS) {
            if (lower.contains(contains)) {
                return true;
            }
        }
        // 纯分隔线 / 空装饰行
        return line.matches("^[-=_*·•\\s]+$");
    }

    private static boolean containsAny(String lowerLine, String[] markers) {
        for (String m : markers) {
            if (lowerLine.contains(m)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断该行是否包含「文件路径:行:列」这种可定位的信息。
     */
    private static boolean hasFileLocation(String line) {
        // 形如 src/App.vue:12:5  或  /abs/path/x.ts:3:1  或  index.js(12,5)
        return line.matches(".*[\\w./\\\\-]+\\.(vue|ts|tsx|js|jsx|css|scss|less|json|html):\\d+[:\\d]*.*")
                || line.matches(".*[\\w./\\\\-]+\\.(vue|ts|tsx|js|jsx):\\d+.*");
    }

    private static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "\n...(错误信息过长，已截断)...";
    }

    /**
     * 统计错误行数，用于日志与进度提示。
     */
    public static int countErrorLines(String rawOutput) {
        if (StrUtil.isBlank(rawOutput)) {
            return 0;
        }
        int count = 0;
        for (String line : rawOutput.split("\\R")) {
            String cleaned = cleanLine(line);
            if (cleaned.isEmpty() || isNoise(cleaned)) {
                continue;
            }
            if (containsAny(cleaned.toLowerCase(), ERROR_MARKERS)) {
                count++;
            }
        }
        return count;
    }
}
