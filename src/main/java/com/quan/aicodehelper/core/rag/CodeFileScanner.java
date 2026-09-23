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
 * 工程目录扫描器：把磁盘上的工程读成一批「可索引的源文件」。
 *
 * <p><b>它不是简单的目录遍历，过滤规则才是它的主体</b>。索引整个
 * {@code node_modules} 的后果不是「慢一点」，而是：</p>
 * <ul>
 *   <li>片段数量从几百涨到几万，<b>检索质量直接崩塌</b> ——
 *       第三方库代码和用户需求毫无关系，却会因为量大而挤满 Top-K；</li>
 *   <li>索引成本失去控制 —— 每次向量化都按片段计费；</li>
 *   <li>增量判断被无意义地拖慢。</li>
 * </ul>
 * <p>所以有了 {@link #SKIP_DIR_NAMES} 与扩展名白名单这两道闸。</p>
 */
public final class CodeFileScanner {

    private CodeFileScanner() {
    }

    /**
     * 按目录名整体跳过的目录。
     *
     * <p>除 {@code node_modules} 与 {@code dist} 这类构建产物外，
     * 还刻意排除了 {@code target} / {@code build} / {@code out} 等 ——
     * 它们是「别人生成的代码」，不是「用户此刻要改的代码」。</p>
     */
    private static final Set<String> SKIP_DIR_NAMES = Set.of(
            "node_modules", "dist", "build", "target", "out", "coverage",
            "vendor", "__pycache__", "logs", "tmp", "temp");

    /**
     * 锁文件：动辄几万行、全是校验和，既无检索价值又会吃掉大量片段名额。
     */
    private static final Set<String> SKIP_FILE_NAMES = Set.of(
            "package-lock.json", "pnpm-lock.yaml", "yarn.lock",
            "npm-shrinkwrap.json", "bun.lockb");

    /**
     * 可索引的文本类扩展名白名单。
     *
     * <p>用白名单而不是黑名单：二进制文件（图片、字体、wasm）一旦被当文本读，
     * 要么解码失败、要么读出一堆乱码喂给嵌入模型 —— 后者不会报错，
     * 只会静默污染索引。白名单让「不认识的一律不索引」，
     * 代价是漏掉某些小众语言，而这个代价是可接受的。</p>
     */
    private static final Set<String> INDEXABLE_EXTENSIONS = Set.of(
            "java", "kt", "groovy", "scala",
            "js", "mjs", "cjs", "jsx", "ts", "tsx", "vue", "svelte",
            "html", "htm", "css", "scss", "sass", "less", "styl",
            "json", "jsonc", "yml", "yaml", "toml", "ini", "properties",
            "xml", "svg", "md", "markdown", "txt",
            "sql", "py", "go", "rs", "rb", "php", "cs", "swift", "dart",
            "c", "h", "cpp", "hpp", "sh", "bat", "ps1", "gradle", "proto");

    /**
     * 一个待索引的源文件。
     *
     * @param relativePath 相对工程根的路径，统一用正斜杠
     * @param content      文件全文
     * @param fileHash     文件内容的 SHA-256，增量索引的判断依据
     */
    public record SourceFile(String relativePath, String content, String fileHash) {
    }

    /**
     * 扫描工程目录。
     *
     * <p>返回结果按路径排序 —— 顺序稳定才能让「同一份代码两次索引得到同样的片段编号」，
     * 否则每次重建索引都会使 chunkIndex 全量漂移，增量比对退化成全量重算。</p>
     *
     * <p>单个文件读取失败只跳过该文件，不影响整体扫描：一个乱码文件
     * 不该让整个工程的检索能力失效。</p>
     *
     * @param root         工程根目录
     * @param maxFileBytes 单文件大小上限，超过则跳过
     * @return 可索引文件列表；目录不存在时返回空列表
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
            // 目录遍历整体失败（权限、路径过长等）：返回已收集到的部分，
            // 让调用方按「索引不完整」继续工作，而不是整条链路失败
            return result;
        }
        return result;
    }

    private static boolean isSkipped(Path path, Path root) {
        Path relative = root.relativize(path);
        for (Path segment : relative) {
            String name = segment.toString();
            if (name.startsWith(".")) {
                // 隐藏目录与隐藏文件一律跳过：.git / .idea / .vscode 之外，
                // 也顺带挡掉了 .eslintrc、.env 这类与需求无关的配置
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
            // 含非法 UTF-8 字节的文件会在这里被挡下 —— 宁可少索引一个文件，
            // 也不要把乱码喂进嵌入模型
            return null;
        }
    }

    /**
     * 统一换行为 {@code \n}。
     *
     * <p>Windows 上生成的代码是 CRLF，若不归一，同一份逻辑内容会因为
     * 换行符不同而算出不同的摘要 —— 每次索引都判定「文件变了」，
     * 增量机制直接失效。</p>
     */
    public static String normalize(String content) {
        return content.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * 相对路径统一用正斜杠，保证 Windows 与 Linux 下产出同样的 filePath —
     * 否则同一份工程在两台机器上会得到两套不同的片段标识。
     */
    private static String toRelativePath(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static String hash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，正常不会走到这里
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
