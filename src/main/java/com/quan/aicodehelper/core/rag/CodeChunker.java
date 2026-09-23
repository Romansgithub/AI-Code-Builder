package com.quan.aicodehelper.core.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 代码切块器：把整份文件切成可被检索的片段。先按语义边界切，单块过长才退回行窗；用关键字判边界而非引语言解析器，漏判只会让片段大些、不会出错。
 */
public final class CodeChunker {

    private CodeChunker() {
    }

    /** 顶层声明边界（Java/TS/JS/Python/Go 等）。缩进放宽到 4 空格，让「类里的方法」也成边界，而方法体内 8 空格语句不命中。 */
    private static final Pattern DECLARATION_BOUNDARY = Pattern.compile(
            "^\\s{0,4}(?:@[A-Za-z_$]"
                    + "|(?:public|private|protected|internal|static|final|abstract|sealed|"
                    + "export|async|declare|default|function|class|interface|enum|record|"
                    + "struct|impl|trait|func|def|type|const|let|var|module|namespace)\\b)");

    private static final Pattern VUE_BLOCK_BOUNDARY = Pattern.compile(
            "^\\s*<(?:template|script|style)\\b");

    private static final Pattern HTML_BLOCK_BOUNDARY = Pattern.compile(
            "^\\s*<(?:head|body|script|style|main|header|footer|nav|section)\\b");

    private static final Pattern CSS_RULE_BOUNDARY = Pattern.compile(
            "^[^\\s}@/].*\\{\\s*$");

    /**
     * 切一个文件，返回按 chunkIndex 升序的片段列表（内容为空返回空）；rawContent 内部归一换行。
     *
     * @param maxLines     单片段软上限，超过行窗二次切分
     * @param overlapLines 行窗重叠行数
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

    /** 按行切分，末尾换行不计为一行，否则行号标签与编辑器对不上。 */
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

    /** 按语义边界切段，返回若干 [起始行, 结束行]（0 基闭区间）。 */
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
        // 识别不了声明边界就整份当一段
        return DECLARATION_BOUNDARY;
    }

    /**
     * 把过长的一段按行窗切开，步长 maxLines-overlapLines，重叠部分同属相邻两片段，避免切点落在函数体中间时上下文不足；步长须为正，否则会死循环（由配置层保证）。
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
