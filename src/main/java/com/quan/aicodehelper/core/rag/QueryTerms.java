package com.quan.aicodehelper.core.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检索用的术语处理：查询侧的分词与命中打分，以及索引侧的标识符抽取。
 *
 * <p><b>为什么需要它 —— 纯向量的短板</b>：用户说的是自然语言
 * （「把登录按钮改成蓝色」），要命中的是代码
 * （{@code <el-button type="primary">登录</el-button>}）。
 * 两者在向量空间里并不天然接近，尤其当需求里的词与代码里的标识符
 * 完全不重合时（「蓝色」对 {@code type="primary"}）。
 * 关键词那一路正是用来兜住这类漏召回的：
 * <b>向量管「意思像」，关键词管「字面对得上」</b>。</p>
 *
 * <p><b>中文为什么要切二元组</b>：中文没有天然词边界，不引入分词器
 * （词典体积与歧义成本都不划算）的前提下，取 2 字滑窗是最省事且够用的近似 ——
 * 「把登录按钮改成蓝色」能被切成「登录」「按钮」「蓝色」这些真正有意义的片段，
 * 而整串拿去匹配则永远命中不了。</p>
 */
public final class QueryTerms {

    private QueryTerms() {
    }

    /**
     * 拉丁文标识符片段。
     */
    private static final Pattern LATIN_TOKEN = Pattern.compile("[A-Za-z0-9_$]+");

    /**
     * 中文串。
     */
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fa5]{2,}");

    /**
     * 查询侧过滤掉的词：英文虚词 + 编程语言关键字。
     *
     * <p>不过滤的话，「把 the 改成 xxx」里的 the、以及用户顺口提到的
     * {@code class}/{@code function} 会命中几乎所有代码片段，
     * 把关键词得分拉平，等于这一路权重白给。</p>
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
     * <p>⚠️ 这个集合用 {@code Set.of} 构造，<b>元素绝不允许重复</b> ——
     * {@code Set.of} 遇到重复元素会抛 {@code IllegalArgumentException}，
     * 而它发生在类初始化阶段，结果是整个 {@code QueryTerms} 不可用：
     * 首次触发时报 {@code ExceptionInInitializerError}，之后所有调用
     * 都变成 {@code NoClassDefFoundError}，检索功能整体静默失效。
     * 2026-09-22 写这个类时 "class" 恰好写了两遍，就是被
     * {@code QueryTermsTest} / {@code CodeChunkerTest} 当场抓出来的。
     * 往这里加词时请确认没有重复。</p>
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
     * <p>⚠️ 2026-09-23 实测踩坑：用户写「把 Navbar.vue 里那个导航项改一下」时，
     * 分词会把 {@code vue} 一并抽成术语。后果有两条，都不是小问题：</p>
     * <ul>
     *   <li><b>关键词得分被拉平</b>：{@code vue} 命中工程里<b>每一个</b> .vue 文件的路径，
     *       每个文件白拿 1.0 的分子，真正的差异被同比例稀释；</li>
     *   <li><b>点名加分退化成按扩展名批发</b>：{@code mentionsFile} 会因为
     *       {@code vue} 命中路径而认为「这个文件被点名了」—— 首轮 16 条用例的实测里，
     *       8 条点名型用例的「加分文件数」恰好等于全工程的 .vue 文件数（8/14、9/15），
     *       即所有 Vue 文件都拿到了 +0.5。加成分原本是 0.5，比一整份加权得分
     *       （0..1）的一半还大，一旦拿到就绝对领先 —— 于是它实际上变成了
     *       「.vue 文件整体排在 .js/.css/.html 之前」，与「点名」毫无关系。</li>
     * </ul>
     * <p>剔除它们不会伤到真正的需求术语：用户不会用「js」「css」去指代某段逻辑。</p>
     */
    private static final Set<String> FILE_EXTENSION_WORDS = Set.of(
            "vue", "js", "ts", "jsx", "tsx", "mjs", "cjs", "svelte",
            "java", "kt", "py", "go", "rb", "php", "cs", "swift", "dart", "rs",
            "html", "htm", "css", "scss", "sass", "less", "styl",
            "json", "yml", "yaml", "toml", "ini", "xml", "svg", "md", "txt",
            "sh", "bat", "ps1", "gradle", "proto");

    /**
     * 索引侧每个片段最多保留的标识符数量。
     */
    private static final int MAX_SYMBOLS = 60;

    /**
     * symbols 字段的长度上限，与建表 DDL 的 VARCHAR(512) 对齐。
     */
    private static final int MAX_SYMBOLS_CHARS = 500;

    /**
     * 从用户需求里抽取用于匹配的术语。
     *
     * <p>扩展名（{@code vue} / {@code js} / {@code css} …）在最后被剔除，
     * 理由见 {@link #FILE_EXTENSION_WORDS}。但若剔除后一个术语都不剩，
     * 则退回剔除前的集合 —— 术语集为空会让检索器直接放弃检索，
     * 那比「分数被一个通用词稀释」严重得多。</p>
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
     * 驼峰拆词：{@code LoginButton → [Login, Button]}。
     *
     * <p>不拆的话，「把 LoginButton 改成红色」只能靠整词命中；
     * 而代码里可能是 {@code login-button} 或 {@code loginBtn}，
     * 拆开后才有可能对上。</p>
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
     * 抽取一段代码里的标识符清单，供关键词打分使用。
     *
     * <p>同时收集中文串：生成出来的界面代码里，中文往往正是最能对上的线索
     * （用户说「登录」，代码里就有「登录」二字）。</p>
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
     * 关键词命中率，落在 [0, 1]。
     *
     * <p>三档权重体现的是命中位置的「可信度」：</p>
     * <ul>
     *   <li><b>命中路径</b>（1.0）—— 用户说了 {@code Login.vue}，这就是最硬的信号；</li>
     *   <li><b>命中标识符</b>（0.85）—— 对上了函数名/变量名，说明大概率是同一处逻辑；</li>
     *   <li><b>命中正文</b>（0.4）—— 可能只是字符串字面量或注释里出现，
     *       比如中文字面量，有价值但不如标识符可靠。</li>
     * </ul>
     *
     * <p>分母固定为「查询术语总数」而非「命中的术语数」：后者会让
     * 「只命中一个词」的片段拿到满分，把只匹配了只言片语的无关代码排到前面。</p>
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
     * 需求里是否直接点名了这个文件。
     *
     * <p>这是比语义相似度更硬的信号，单独加成：用户说「改一下 Login.vue 的标题」时，
     * 那个文件必须进上下文 —— 无论向量算出来的相似度是多少。</p>
     *
     * <p><b>为什么必须「文件名主干相等」而不是「术语出现在路径里」</b>：
     * 后者看着宽松无害，实测却是灾难性的。首轮评测（2026-09-23，16 条用例）里
     * 8 条点名型用例的加分文件数恰好等于全工程 .vue 文件数 ——
     * 因为 {@code vue} 这个扩展名词出现在每一条 .vue 路径里，于是<b>所有 Vue 文件
     * 都被判成「被点名」</b>，各得 +0.5。加成 0.5 大于一整份加权得分的一半，
     * 拿到就绝对领先，净效果成了「按扩展名批发排序」：</p>
     * <ul>
     *   <li>点名型 100% 的召回因此不可信 —— 它对期望文件和它的竞争者是同等生效的；</li>
     *   <li>典型案例：查询点名了 {@code Navbar.vue}，排第一的却是
     *       {@code Attractions.vue}（两者都拿到 +0.5，只剩向量/关键词在比）。</li>
     * </ul>
     * <p>改成相等匹配后，加成才回到「定向」语义：只有真的写了那个文件名的用例才加分。
     * 代价是「Card 组件」这类简称不再触发加成 —— 宁可漏掉一次加成（缺了它的检索仍然
     * 按向量与关键词排序），也不要给整个工程批发加成（那会让所有指标失去鉴别力）。</p>
     *
     * <p>比较前做归一化（只留字母数字、转小写），所以
     * {@code login-page.vue} / {@code LoginPage.vue} / {@code login_page} 视为同一个名字。</p>
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
     * 「全局样式」扩展名。这类文件影响整站外观，任何「改一改界面」的需求
     * 都与它有点关系 —— 它的向量相似度因此天然偏高，判别力却很低。
     */
    private static final Set<String> STYLE_EXTENSIONS = Set.of(
            "css", "scss", "sass", "less", "styl");

    /**
     * 这个文件是否属于「横切文件」（cross-cutting）。
     *
     * <p><b>它为什么值得单独识别</b>：第三轮实测（2026-09-23，28 条用例）里，
     * 语义型 20 条中有 8 条虽然召回了期望文件，但首位被占 —— 其中 7 条的占位者
     * 是 {@code src/styles/main.css}（4 次）与 {@code About.vue}（3 次）。
     * {@code main.css} 是全站样式，<b>对任何界面需求都有不低的余弦相似度</b>，
     * 这类文件的相似度不说明「它就是这个需求要改的」。</p>
     *
     * <p><b>⚠️ 判据只剩「全局样式扩展名」一条，这是第四轮实测砍剩下的。</b>
     * 初版还有第二条「框架约定入口名」（主干 ∈ {@code main / index / app}），
     * 当时的理由是「路径固定、任何 Vue 工程都有，不算为本工程调参」——
     * 这个理由听着成立，实测是错的。折扣开启（0.9）后仅向量与混合组的
     * <b>漏召由 1 条涨到 3 条</b>（Recall@8 96.4% → 89.3%），新增的两条
     * （{@code shop-app-semantic-13}「页脚版权信息补上公司全称」、
     * {@code tour-transition-semantic-18}「页面切换加淡入淡出」）
     * 期望文件<b>都是 {@code src/App.vue}</b>，且都因入口名规则被降了权。</p>
     *
     * <p>错在把「<b>位置恒定</b>」当成了「<b>内容泛化</b>」，两者不是一回事：
     * 全站样式是<b>横切关注点</b>，跟任何需求都沾一点边；而入口文件（{@code App.vue}）
     * 承载的是根布局、页脚、路由过渡 —— 需求<b>恰恰好</b>落在这些地方的概率很高。
     * 它是正确答案，不是「对谁都像」。</p>
     *
     * <p><b>它不是文件白名单</b>：判据是结构性规则（扩展名），
     * 没有任何本工程特有的文件名。</p>
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
     * 横切文件在<b>向量分</b>上应乘的系数。
     *
     * <p><b>为什么只削向量分</b>：关键词分是「字面对得上」的硬信号
     * （路径 1.0 / 标识符 0.85 / 正文 0.4），它与「这个文件是否泛化」无关；
     * 泛化的来源正是「对谁都像」的向量相似度。点名加成同理不能碰 ——
     * 用户写下 {@code main.css} 时，他就是明确要改它。</p>
     *
     * <p><b>默认不打折，且必须能打折到 0 以外的原因</b>：折扣会牺牲
     * 「明确要改全局文件、却没说文件名」的需求（实测里
     * 「默认字体加载太慢，想换成系统自带的」就属于这类，期望文件正是
     * {@code main.css}）。因此它是一个需要实测对比才敢打开的旋钮，
     * 而不是一个「装了就该用」的开关：</p>
     * <ul>
     *   <li>{@code configuredPenalty} ≥ 1 或 ≤ 0（含 NaN）→ 一律返回 1.0，等于关闭；</li>
     *   <li>文件不是横切文件 → 1.0；</li>
     *   <li>文件被点名（{@link #mentionsFile}）→ 1.0，点名优先于降权。</li>
     * </ul>
     *
     * <p><b>⚠️ 判据收窄之后，当前仍建议保持关闭</b>（第四轮实测结论）：「排后」的代价
     * 接近于零 —— Top-K 没被占满时，被抢走首位的期望文件照样进上下文，它占的是
     * 字符预算与模型注意力，不是正确文件的位置；而折扣换来的是真实的降权风险。
     * 那一轮开 0.9 的净账是 Top-1 +1、Recall −2，即<b>拿零代价的东西换来了真代价</b>。
     * 而且 17 号（期望 {@code main.css}）的期望文件仍排第 2，榜首被
     * {@code package.json} 占走（{@code kw=0}，纯向量）—— 榜首位置是<b>竞争性</b>的，
     * 压下一个总有下一个补位。它的定位是「工程变大、文件数逼近 Top-K 时的预防手段」，
     * 不是当前缺口的修复。</p>
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

    /**
     * 取文件扩展名（小写，不含点）；无扩展名时返回空串。
     */
    private static String fileExtension(String filePath) {
        String path = filePath.toLowerCase(Locale.ROOT);
        int slash = path.lastIndexOf('/');
        String fileName = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot + 1) : "";
    }

    /**
     * 取文件名主干：去掉目录部分与扩展名。
     */
    private static String fileBaseName(String filePath) {
        String path = filePath.toLowerCase(Locale.ROOT);
        int slash = path.lastIndexOf('/');
        String fileName = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * 归一化文件名/术语：只保留字母与数字。
     *
     * <p>让分隔符与大小写不参与比较 —— 用户写 {@code LoginPage}、
     * 文件叫 {@code login-page.vue} 是同一件事。</p>
     */
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
