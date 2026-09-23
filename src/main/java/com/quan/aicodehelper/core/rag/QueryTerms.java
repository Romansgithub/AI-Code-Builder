package com.quan.aicodehelper.core.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 查询侧的分词与关键词命中打分，以及索引侧的标识符抽取。
 *
 * <p>关键词一路与向量互补：向量管意思像，关键词管字面对得上。
 * 中文没有词边界，这里不引入分词器，用 2 字滑窗近似。</p>
 */
public final class QueryTerms {

    private QueryTerms() {
    }

    private static final Pattern LATIN_TOKEN = Pattern.compile("[A-Za-z0-9_$]+");

    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fa5]{2,}");

    /**
     * 查询侧过滤掉的词：不滤掉的话，the、class 这类词会命中几乎所有片段，把关键词得分拉平。
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "that", "this", "you", "your", "from", "not",
            "are", "was", "were", "has", "have", "had", "can", "will", "would", "should",
            "please", "make", "use", "using", "add", "new", "all", "any", "but", "out",
            "its", "when", "how", "what", "why", "into", "over", "then", "than",
            "class", "function", "return", "import", "export", "default", "const",
            "let", "var", "void", "public", "private", "protected", "static", "final",
            "true", "false", "null", "undefined", "string", "number", "boolean", "int",
            "def", "self", "package", "extends", "implements", "interface", "enum");

    /**
     * 代码里出现但对检索无意义的标识符（语言关键字、常用内置名）。
     *
     * <p>注意 {@code Set.of} 不允许重复元素，重复会在类初始化时抛异常，导致整个类的后续调用
     * 全部失败。往这里加词时确认没有重复。</p>
     */
    private static final Set<String> CODE_NOISE = Set.of(
            "public", "private", "protected", "static", "final", "abstract",
            "class", "interface", "enum", "record", "extends", "implements",
            "package", "import", "return", "void", "new", "this", "super",
            "null", "true", "false", "var", "let", "const", "function", "export",
            "default", "async", "await", "typeof", "instanceof", "throw", "throws",
            "try", "catch", "finally", "switch", "case", "break", "continue",
            "for", "while", "else", "def", "self",
            "string", "number", "boolean", "object", "array", "any", "unknown",
            "template", "script", "style", "div", "span", "button",
            "html", "css", "javascript", "typescript", "vue");

    /**
     * 扩展名不是标识符，必须从查询术语里剔除。
     *
     * <p>否则 {@code vue} 会命中工程里每一条 .vue 路径：关键词得分被拉平，且点名加成
     * 会退化成按扩展名批发（所有 .vue 文件都算被点名，各拿一份加成）。</p>
     */
    private static final Set<String> FILE_EXTENSION_WORDS = Set.of(
            "vue", "js", "ts", "jsx", "tsx", "mjs", "cjs", "svelte",
            "java", "kt", "py", "go", "rb", "php", "cs", "swift", "dart", "rs",
            "html", "htm", "css", "scss", "sass", "less", "styl",
            "json", "yml", "yaml", "toml", "ini", "xml", "svg", "md", "txt",
            "sh", "bat", "ps1", "gradle", "proto");

    private static final int MAX_SYMBOLS = 60;

    /** symbols 字段的长度上限，与建表 DDL 的 VARCHAR(512) 对齐。 */
    private static final int MAX_SYMBOLS_CHARS = 500;

    /**
     * 从用户需求里抽取用于匹配的术语。
     *
     * <p>扩展名在最后剔除；若剔除后一个术语都不剩，则退回剔除前的集合
     * （术语集为空会让检索器直接放弃检索，比分数被稀释严重）。</p>
     */
    public static Set<String> extract(String query) {
        Set<String> terms = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return terms;
        }
        collectLatinTerms(query, terms);
        collectChineseTerms(query, terms);

        Set<String> useful = new LinkedHashSet<>();
        for (String term : terms) {
            if (!FILE_EXTENSION_WORDS.contains(term)) {
                useful.add(term);
            }
        }
        return useful.isEmpty() ? terms : useful;
    }

    private static void collectLatinTerms(String query, Set<String> terms) {
        Matcher matcher = LATIN_TOKEN.matcher(query);
        while (matcher.find()) {
            String raw = matcher.group();
            // 先按 _ 和 $ 拆（login_vue / app$state），再拆驼峰（LoginButton）
            for (String part : raw.split("[_$]+")) {
                if (part.isEmpty()) {
                    continue;
                }
                addIfUseful(part.toLowerCase(Locale.ROOT), terms);
                for (String camelPart : splitCamel(part)) {
                    addIfUseful(camelPart, terms);
                }
            }
        }
    }

    /**
     * 驼峰拆词：{@code LoginButton → [Login, Button]}，拆开才能对上
     * {@code login-button} / {@code loginBtn} 这类写法。
     */
    private static List<String> splitCamel(String token) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isUpperCase(c) && current.length() > 0) {
                parts.add(current.toString().toLowerCase(Locale.ROOT));
                current.setLength(0);
            }
            current.append(c);
        }
        if (current.length() > 0) {
            parts.add(current.toString().toLowerCase(Locale.ROOT));
        }
        return parts;
    }

    private static void collectChineseTerms(String query, Set<String> terms) {
        Matcher matcher = CJK_RUN.matcher(query);
        while (matcher.find()) {
            String run = matcher.group();
            if (run.length() <= 4) {
                addIfUseful(run, terms);
            }
            for (int i = 0; i + 2 <= run.length(); i++) {
                addIfUseful(run.substring(i, i + 2), terms);
            }
        }
    }

    private static void addIfUseful(String term, Set<String> terms) {
        if (term == null || term.length() < 2) {
            return;
        }
        if (STOP_WORDS.contains(term)) {
            return;
        }
        terms.add(term);
    }

    /**
     * 抽取一段代码里的标识符清单，供关键词打分使用；中文串一并收集
     * （生成的界面代码里，中文往往正是最能对上的线索）。
     */
    public static String symbolsOf(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        LinkedHashSet<String> symbols = new LinkedHashSet<>();
        Matcher latin = LATIN_TOKEN.matcher(code);
        while (latin.find() && symbols.size() < MAX_SYMBOLS) {
            String token = latin.group();
            if (token.length() < 3) {
                continue;
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if (CODE_NOISE.contains(lower)) {
                continue;
            }
            symbols.add(lower);
        }
        Matcher chinese = CJK_RUN.matcher(code);
        while (chinese.find() && symbols.size() < MAX_SYMBOLS) {
            symbols.add(chinese.group());
        }
        StringBuilder builder = new StringBuilder();
        for (String symbol : symbols) {
            if (builder.length() + symbol.length() + 1 > MAX_SYMBOLS_CHARS) {
                break;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(symbol);
        }
        return builder.toString();
    }

    /**
     * 关键词命中率，落在 [0, 1]。三档权重按命中位置的可信度：路径 1.0、标识符 0.85、正文 0.4。
     *
     * <p>分母固定为查询术语总数而非命中数：后者会让只命中一个词的片段拿满分。</p>
     *
     * @return 命中率；无术语时返回 0
     */
    public static double matchScore(Set<String> terms, String symbols, String filePath, String content) {
        if (terms == null || terms.isEmpty()) {
            return 0D;
        }
        String pathLower = filePath == null ? "" : filePath.toLowerCase(Locale.ROOT);
        String symbolsLower = symbols == null ? "" : symbols.toLowerCase(Locale.ROOT);
        String contentLower = content == null ? "" : content.toLowerCase(Locale.ROOT);

        double total = 0D;
        for (String term : terms) {
            if (pathLower.contains(term)) {
                total += 1.0D;
            } else if (symbolsLower.contains(term)) {
                total += 0.85D;
            } else if (contentLower.contains(term)) {
                total += 0.4D;
            }
        }
        return Math.min(1D, total / terms.size());
    }

    /**
     * 需求里是否直接点名了这个文件；是则单独加成。
     *
     * <p>判据必须是「文件名主干归一化后相等」，不能用「术语出现在路径里」：路径包含看着宽松，
     * 却会让出现在每条路径里的扩展名词（{@code vue}）给全工程同类文件批发加成，
     * 加成一旦拿到就绝对领先，点名语义随之失效。代价是「Card 组件」这类简称不再触发加成，
     * 可接受。</p>
     *
     * <p>比较前归一化（只留字母数字、转小写），所以 {@code login-page.vue} 与
     * {@code LoginPage.vue} 视为同名。</p>
     */
    public static boolean mentionsFile(Set<String> terms, String filePath) {
        if (terms == null || terms.isEmpty() || filePath == null || filePath.isEmpty()) {
            return false;
        }
        String baseName = normalizeName(fileBaseName(filePath));
        if (baseName.length() < 3) {
            return false;
        }
        for (String term : terms) {
            if (term == null || term.length() < 3) {
                continue;
            }
            if (baseName.equals(normalizeName(term))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 全局样式扩展名：这类文件影响整站外观，对任何界面需求都有偏高的余弦相似度，
     * 判别力却很低。
     */
    private static final Set<String> STYLE_EXTENSIONS = Set.of(
            "css", "scss", "sass", "less", "styl");

    /**
     * 是否属于横切文件。判据只有扩展名一条结构性规则，不含任何本工程特有的文件名。
     *
     * <p>曾加过「框架入口名（main / index / app）」这一条，实测被否：它把「位置恒定」
     * 当成了「内容泛化」，而入口文件承载根布局、页脚、路由过渡，需求落在那里本是常态。
     * 详见 docs/design-notes.md 第五节的第四轮记录。</p>
     *
     * @see #genericFilePenalty(Set, String, double) 折扣怎么施加
     */
    public static boolean isGenericFile(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return false;
        }
        return STYLE_EXTENSIONS.contains(fileExtension(filePath));
    }

    /**
     * 横切文件在向量分上应乘的系数。
     *
     * <p>只削向量分：关键词分是字面命中的硬信号，与文件是否泛化无关；泛化正来自对谁都像的
     * 向量相似度。点名加成也不碰，用户写下 {@code main.css} 时他要改的就是它。</p>
     *
     * <p>取值 ≥ 1 或 ≤ 0（含 NaN）一律返回 1.0，即关闭。默认为关闭：排后本身不产生漏召，
     * 而被折扣压下去的文件可能是正确答案（「默认字体加载太慢」期望的正是 {@code main.css}），
     * 它是工程规模逼近 topK 时的预防手段，不是当前缺口的修复。</p>
     *
     * @param configuredPenalty 配置的折扣系数，建议区间 0.85 ~ 0.95
     * @return 应乘在向量分上的系数
     */
    public static double genericFilePenalty(Set<String> terms, String filePath,
                                           double configuredPenalty) {
        if (!(configuredPenalty > 0D) || configuredPenalty >= 1.0D) {
            return 1.0D;
        }
        if (!isGenericFile(filePath) || mentionsFile(terms, filePath)) {
            return 1.0D;
        }
        return configuredPenalty;
    }

    private static String fileExtension(String filePath) {
        String path = filePath.toLowerCase(Locale.ROOT);
        int slash = path.lastIndexOf('/');
        String fileName = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot + 1) : "";
    }

    private static String fileBaseName(String filePath) {
        String path = filePath.toLowerCase(Locale.ROOT);
        int slash = path.lastIndexOf('/');
        String fileName = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String normalizeName(String name) {
        StringBuilder builder = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                builder.append(Character.toLowerCase(c));
            }
        }
        return builder.toString();
    }
}
