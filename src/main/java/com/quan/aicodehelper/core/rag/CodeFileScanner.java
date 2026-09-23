package com.quan.aicodehelper.core.rag;

import cn.hutool.core.util.StrUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 工程目录扫描器：把磁盘工程读成一批可索引源文件。过滤规则是主体：索引整个 node_modules 会让
 * 第三方库挤满 Top-K、检索质量崩塌，且每次向量化都计费。
 */
public final class CodeFileScanner {

    private CodeFileScanner() {
    }

    private static final Set<String> SKIP_DIR_NAMES = Set.of(
            "node_modules", "dist", "build", "target", "out", "coverage",
            "vendor", "__pycache__", "logs", "tmp", "temp");

    /** 锁文件：体积大、无检索价值，还会挤占片段名额。 */
    private static final Set<String> SKIP_FILE_NAMES = Set.of(
            "package-lock.json", "pnpm-lock.yaml", "yarn.lock",
            "npm-shrinkwrap.json", "bun.lockb");

    /** 可索引文本扩展名白名单。白名单而非黑名单：二进制被当文本读不报错，只会静默污染索引。 */
    private static final Set<String> INDEXABLE_EXTENSIONS = Set.of(
            "java", "kt", "groovy", "scala",
            "js", "mjs", "cjs", "jsx", "ts", "tsx", "vue", "svelte",
            "html", "htm", "css", "scss", "sass", "less", "styl",
            "json", "jsonc", "yml", "yaml", "toml", "ini", "properties",
            "xml", "svg", "md", "markdown", "txt",
            "sql", "py", "go", "rs", "rb", "php", "cs", "swift", "dart",
            "c", "h", "cpp", "hpp", "sh", "bat", "ps1", "gradle", "proto");

    /** 一个待索引的源文件：relativePath 统一正斜杠，fileHash 为文件 SHA-256、增量索引的判断依据。 */
    public record SourceFile(String relativePath, String content, String fileHash) {
    }

    /**
     * 扫描工程目录，结果按路径排序，保证两次索引得到相同片段编号（否则增量退化成全量）。
     *
     * @param maxFileBytes 单文件大小上限，超过跳过
     * @return 可索引文件列表；目录不存在返回空列表；单文件失败只跳过该文件
     */
    public static List<SourceFile> scan(Path root, long maxFileBytes) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        List<SourceFile> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, 24)) {
            List<Path> candidates = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isSkipped(p, root))
                    .filter(p -> isIndexable(p))
                    .filter(p -> sizeWithin(p, maxFileBytes))
                    .sorted(Comparator.comparing(p -> toRelativePath(root, p)))
                    .toList();
            for (Path candidate : candidates) {
                SourceFile file = read(root, candidate);
                if (file != null) {
                    result.add(file);
                }
            }
        } catch (IOException e) {
            // 目录遍历整体失败（权限、路径过长等）：返回已收集到的部分，而非整条链路失败
            return result;
        }
        return result;
    }

    private static boolean isSkipped(Path path, Path root) {
        Path relative = root.relativize(path);
        for (Path segment : relative) {
            String name = segment.toString();
            if (name.startsWith(".")) {
                // 隐藏目录/文件一律跳过（.git、.env 等），与需求无关
                return true;
            }
            if (SKIP_DIR_NAMES.contains(name.toLowerCase())) {
                return true;
            }
        }
        return SKIP_FILE_NAMES.contains(path.getFileName().toString().toLowerCase());
    }

    private static boolean isIndexable(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        return INDEXABLE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
    }

    private static boolean sizeWithin(Path path, long maxFileBytes) {
        try {
            return Files.size(path) <= maxFileBytes;
        } catch (IOException e) {
            return false;
        }
    }

    private static SourceFile read(Path root, Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (StrUtil.isBlank(content)) {
                return null;
            }
            String relativePath = toRelativePath(root, path);
            return new SourceFile(relativePath, normalize(content), hash(content));
        } catch (Exception e) {
            // 含非法 UTF-8 字节的文件挡下，宁少索引也不把乱码喂进嵌入模型
            return null;
        }
    }

    /** 统一换行为 {@code \n}；否则 CRLF 会让同一内容算出不同摘要，增量机制失效。 */
    public static String normalize(String content) {
        return content.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 相对路径统一正斜杠，保证跨平台产出相同的 filePath。 */
    private static String toRelativePath(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static String hash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 为 JDK 必备算法，正常不会走到这里
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
