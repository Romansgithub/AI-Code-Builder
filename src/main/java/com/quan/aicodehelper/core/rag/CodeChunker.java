package com.quan.aicodehelper.core.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 代码切块器：把整份文件切成「可被检索」的片段。
 *
 * <p><b>为什么不能简单地每 N 行切一刀</b>：检索的最小单位是片段，
 * 而模型要改的是「一个函数」「一个块」。固定行窗的切点会随机落在函数体中间，
 * 于是检索回来的片段永远是残缺的一半 —— 模型看到半个函数，
 * 改出来的代码自然对不上。所以顺序是「先按语义边界切，
 * 只在单个语义块仍然过长时才退回行窗切」。</p>
 *
 * <p><b>它是启发式的，不是语法解析</b>：不引语言解析器（Vue / TS / CSS
 * 各有各的解析栈，成本与收益不成比例），而是用「行首声明的关键字」判断边界。
 * 这必然有漏判 —— 一个没有修饰符的 Java 方法不会被识别成新边界。
 * 漏判的后果只是「这个片段大一些，由行窗兜底」，
 * 而不会产生错误内容。这是刻意选择的安全方向。</p>
 */
public final class CodeChunker {

    private CodeChunker() {
    }

    /**
     * 顶层声明边界（Java / TS / JS / Python / Go 等）。
     *
     * <p>缩进放宽到 4 个空格，是为了让「类里的方法」也能成为边界 ——
     * Java 方法在 4 空格、TS 方法在 2 空格，都能命中；而方法体内的语句
     * 通常在 8 空格，不会命中，避免了把语句切碎。</p>
     */
    private static final Pattern DECLARATION_BOUNDARY = Pattern.compile(
            "^\\s{0,4}(?:@[A-Za-z_$]"
                    + "|(?:public|private|protected|internal|static|final|abstract|sealed|"
                    + "export|async|declare|default|function|class|interface|enum|record|"
                    + "struct|impl|trait|func|def|type|const|let|var|module|namespace)\\b)");

    /**
     * Vue 单文件组件的块边界。
     */
    private static final Pattern VUE_BLOCK_BOUNDARY = Pattern.compile(
            "^\\s*<(?:template|script|style)\\b");

    /**
     * HTML 的主要结构边界。
     */
    private static final Pattern HTML_BLOCK_BOUNDARY = Pattern.compile(
            "^\\s*<(?:head|body|script|style|main|header|footer|nav|section)\\b");

    /**
     * CSS 的顶层规则边界（行首开始的一条选择器）。
     */
    private static final Pattern CSS_RULE_BOUNDARY = Pattern.compile(
            "^[^\\s}@/].*\\{\\s*$");

    /**
     * 切一个文件。
     *
     * @param relativePath 相对工程根路径（用于片段定位，不参与切分）
     * @param rawContent   文件原文（内部会做换行归一）
     * @param fileHash     整个文件的摘要，会写进每个片段
     * @param maxLines     单片段软上限行数，超过则行窗二次切分
     * @param overlapLines 行窗二次切分时的重叠行数
     * @return 片段列表，按 chunkIndex 升序；内容为空时返回空列表
     */
    public static List<CodeSlice> chunk(String relativePath, String rawContent, String fileHash,
                                        int maxLines, int overlapLines) {
        if (rawContent == null || rawContent.isEmpty()) {
            return List.of();
        }
        List<String> lines = splitLines(CodeFileScanner.normalize(rawContent));
        if (lines.isEmpty()) {
            return List.of();
        }

        List<int[]> segments = splitByBoundary(relativePath, lines);
        List<int[]> windows = new ArrayList<>();
        for (int[] segment : segments) {
            windows.addAll(windowize(segment[0], segment[1], maxLines, overlapLines));
        }

        List<CodeSlice> slices = new ArrayList<>(windows.size());
        for (int i = 0; i < windows.size(); i++) {
            int[] range = windows.get(i);
            String content = join(lines, range[0], range[1]);
            slices.add(new CodeSlice(
                    relativePath,
                    i,
                    range[0] + 1,
                    range[1] + 1,
                    content,
                    CodeSlice.hashOf(content),
                    fileHash,
                    QueryTerms.symbolsOf(content)));
        }
        return slices;
    }

    /**
     * 按行切分。
     *
     * <p>末尾的换行不算作一行：{@code "a\nb\n"} 应当是 2 行而不是 3 行，
     * 否则每个片段范围都会多出一个空行，行号标签也会与编辑器对不上。</p>
     */
    private static List<String> splitLines(String content) {
        String trimmedTrailing = content.endsWith("\n")
                ? content.substring(0, content.length() - 1)
                : content;
        if (trimmedTrailing.isEmpty()) {
            return List.of();
        }
        return List.of(trimmedTrailing.split("\n", -1));
    }

    private static String join(List<String> lines, int fromInclusive, int toInclusive) {
        StringBuilder builder = new StringBuilder();
        for (int i = fromInclusive; i <= toInclusive; i++) {
            if (i > fromInclusive) {
                builder.append('\n');
            }
            builder.append(lines.get(i));
        }
        return builder.toString();
    }

    /**
     * 按语义边界切段，返回若干 {@code [起始行, 结束行]}（0 基，闭区间）。
     */
    private static List<int[]> splitByBoundary(String relativePath, List<String> lines) {
        Pattern boundary = boundaryPatternOf(relativePath);
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        if (boundary != null) {
            for (int i = 1; i < lines.size(); i++) {
                if (boundary.matcher(lines.get(i)).find()) {
                    starts.add(i);
                }
            }
        }
        List<int[]> segments = new ArrayList<>(starts.size());
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = (i + 1 < starts.size()) ? starts.get(i + 1) - 1 : lines.size() - 1;
            if (to >= from) {
                segments.add(new int[]{from, to});
            }
        }
        return segments;
    }

    private static Pattern boundaryPatternOf(String relativePath) {
        String lower = relativePath == null ? "" : relativePath.toLowerCase();
        if (lower.endsWith(".vue")) {
            return VUE_BLOCK_BOUNDARY;
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".svelte")) {
            return HTML_BLOCK_BOUNDARY;
        }
        if (lower.endsWith(".css") || lower.endsWith(".scss") || lower.endsWith(".less")
                || lower.endsWith(".sass") || lower.endsWith(".styl")) {
            return CSS_RULE_BOUNDARY;
        }
        // 其余按「有没有可识别的声明边界」决定，识别不了就整份当一段
        return DECLARATION_BOUNDARY;
    }

    /**
     * 把过长的一段按行窗切开。
     *
     * <p>步长为 {@code maxLines - overlapLines}，重叠部分同时属于相邻两个片段。
     * 重叠的意义在于：切点恰好落在函数体中间时，两个片段各自都还带着
     * 一定上下文；没有重叠的话，检索到任何一半都不足以让模型改对。</p>
     *
     * <p>步长由配置层保证为正数 —— 一旦为 0 这里会是死循环。</p>
     */
    private static List<int[]> windowize(int from, int to, int maxLines, int overlapLines) {
        int length = to - from + 1;
        if (length <= maxLines) {
            return List.of(new int[]{from, to});
        }
        List<int[]> windows = new ArrayList<>();
        int step = Math.max(1, maxLines - overlapLines);
        int cursor = from;
        while (cursor <= to) {
            int end = Math.min(cursor + maxLines - 1, to);
            windows.add(new int[]{cursor, end});
            if (end == to) {
                break;
            }
            cursor += step;
        }
        return windows;
    }
}
