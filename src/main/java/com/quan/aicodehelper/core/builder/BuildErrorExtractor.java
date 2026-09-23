package com.quan.aicodehelper.core.builder;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 从 npm/vite 构建输出中挑出有诊断价值的行并截断长度，避免噪音污染提示词。 */
public final class BuildErrorExtractor {

    /** 默认字符上限。整段日志塞进提示词会被模型拒掉，必须先截断。 */
    public static final int DEFAULT_MAX_LENGTH = 3000;

    /** 噪音行前缀（不区分大小写）。前缀写宽了会误伤 {@code npm error} 这类真报错。 */
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

    private static final String[] NOISE_CONTAINS = {
            "deprecated",
            "found 0 vulnerabilities",
    };

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
     * @return 提取后的错误文本；未识别出任何错误行时退化为「原输出尾部截断」
     */
    public static String extract(String rawOutput) {
        return extract(rawOutput, DEFAULT_MAX_LENGTH);
    }

    public static String extract(String rawOutput, int maxLength) {
        if (StrUtil.isBlank(rawOutput)) {
            return "";
        }
        String[] lines = rawOutput.split("\\R");
        // 用 LinkedHashSet 去重并保持出现顺序，重复的报错只留一条
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
            // 不能退回原始输出（会把刚过滤的噪音放回来），改取非噪音行尾部；仍为空则交调用方报「未能提取」
            result = tailOfNonNoiseLines(lines, maxLength);
        } else {
            result = String.join("\n", picked);
        }
        return truncate(result, maxLength);
    }

    /** 兜底：不认得错误特征时最后几行最接近真实原因，取非噪音行尾部。 */
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

    private static String cleanLine(String line) {
        if (line == null) {
            return "";
        }
        // vite/rollup 的输出带颜色码，直接进提示词会干扰模型
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
