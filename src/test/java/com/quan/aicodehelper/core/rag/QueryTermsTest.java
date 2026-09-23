package com.quan.aicodehelper.core.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索术语处理测试。
 *
 * <p>这一层是「纯向量不够用」的补丁，所以测试的重点不是「能不能分词」，
 * 而是两条工程约束：中文需求要能被切成能命中的片段（否则中文用户等于没有关键词这一路），
 * 以及高频虚词/语言关键字要被挡住（否则关键词得分被拉平，权重白给）。</p>
 */
@DisplayName("检索术语处理")
class QueryTermsTest {

    @Nested
    @DisplayName("查询分词")
    class ExtractCase {

        @Test
        @DisplayName("中文需求应切成二元组，整串拿去匹配永远命中不了")
        void chineseBecomesBigrams() {
            Set<String> terms = QueryTerms.extract("把登录按钮改成蓝色");

            assertThat(terms).contains("登录", "按钮", "蓝色");
            assertThat(terms).doesNotContain("把登录按钮改成蓝色");
        }

        @Test
        @DisplayName("短中文串应整串保留，避免「蓝色」被拆成无意义单字")
        void shortChineseKeptWhole() {
            assertThat(QueryTerms.extract("改颜色")).contains("改颜色", "改颜", "颜色");
        }

        @Test
        @DisplayName("驼峰标识符应拆出词根 —— 代码里可能写的是 login-btn 或 loginBtn")
        void camelCaseIsSplit() {
            Set<String> terms = QueryTerms.extract("把 LoginButton 改成红色");

            assertThat(terms).contains("loginbutton", "login", "button", "红色");
        }

        @Test
        @DisplayName("下划线命名的标识符应被拆开")
        void underscoreIsSplit() {
            assertThat(QueryTerms.extract("user_profile 加个字段"))
                    .contains("user", "profile");
        }

        @Test
        @DisplayName("虚词与语言关键字应被过滤，否则几乎所有片段都会命中")
        void stopWordsFiltered() {
            Set<String> terms = QueryTerms.extract("please update the class name");

            assertThat(terms).contains("update", "name");
            assertThat(terms).doesNotContain("please", "the", "class");
        }

        @Test
        @DisplayName("单字符词应被丢弃，它们的噪音大于信息量")
        void singleCharDropped() {
            assertThat(QueryTerms.extract("a b c")).isEmpty();
        }

        @Test
        @DisplayName("空白与 null 输入返回空集合，不应抛异常")
        void blankInput() {
            assertThat(QueryTerms.extract(null)).isEmpty();
            assertThat(QueryTerms.extract("   ")).isEmpty();
        }

        @Test
        @DisplayName("扩展名不算术语 —— 否则「Navbar.vue」抽出的 vue 会命中工程里每一个 .vue 文件")
        void extensionWordsAreDropped() {
            Set<String> terms = QueryTerms.extract("把 Navbar.vue 里那个导航项改成景区导览");

            assertThat(terms).contains("navbar");
            assertThat(terms)
                    .as("扩展名不是标识符：留着它会让所有 .vue 文件白拿一份关键词分，差异被稀释掉")
                    .doesNotContain("vue");
        }

        @Test
        @DisplayName("整句都是扩展名时退回原术语集 —— 术语集为空会让检索器直接放弃检索，比分数不准严重")
        void extensionOnlyQueryFallsBack() {
            assertThat(QueryTerms.extract("css")).containsExactly("css");
        }
    }

    @Nested
    @DisplayName("关键词命中率")
    class MatchScoreCase {

        @Test
        @DisplayName("命中路径得分最高 —— 用户点名了文件名是最硬的信号")
        void pathHitWins() {
            assertThat(QueryTerms.matchScore(Set.of("login"), "", "src/views/Login.vue", ""))
                    .isEqualTo(1.0D);
        }

        @Test
        @DisplayName("命中标识符次之（0.85）")
        void symbolHit() {
            assertThat(QueryTerms.matchScore(Set.of("login"), "loginbutton", "src/App.vue", ""))
                    .isEqualTo(0.85D);
        }

        @Test
        @DisplayName("只在正文里出现得分最低（0.4）—— 可能只是字符串字面量")
        void contentHit() {
            assertThat(QueryTerms.matchScore(Set.of("login"), "", "src/App.vue", "const loginForm = 1"))
                    .isEqualTo(0.4D);
        }

        @Test
        @DisplayName("完全没命中得 0")
        void noHit() {
            assertThat(QueryTerms.matchScore(Set.of("登录"), "abc", "src/App.vue", "nothing here"))
                    .isEqualTo(0D);
        }

        @Test
        @DisplayName("分母是查询术语总数，不是命中数 —— 否则只匹配一个词就会拿满分")
        void normalizedByTotalTerms() {
            double score = QueryTerms.matchScore(Set.of("login", "zzzzzz"),
                    "", "src/views/Login.vue", "");

            assertThat(score).isEqualTo(0.5D);
        }

        @Test
        @DisplayName("空术语集返回 0，避免除零")
        void emptyTerms() {
            assertThat(QueryTerms.matchScore(Set.of(), "abc", "a.txt", "abc")).isEqualTo(0D);
            assertThat(QueryTerms.matchScore(null, "abc", "a.txt", "abc")).isEqualTo(0D);
        }

        @Test
        @DisplayName("得分不超过 1，保证与向量分同量纲做加权")
        void cappedAtOne() {
            double score = QueryTerms.matchScore(Set.of("login"),
                    "login", "src/Login.vue", "login everywhere");

            assertThat(score).isLessThanOrEqualTo(1.0D);
        }
    }

    @Nested
    @DisplayName("文件名点名识别")
    class MentionsFileCase {

        @Test
        @DisplayName("需求里出现文件名应识别为点名")
        void detectsFileName() {
            assertThat(QueryTerms.mentionsFile(Set.of("login"), "src/views/Login.vue")).isTrue();
        }

        @Test
        @DisplayName("点名不受大小写与分隔符影响 —— login-page.vue 与 LoginPage.vue 是同一个名字")
        void separatorAndCaseInsensitive() {
            Set<String> terms = QueryTerms.extract("把 LoginPage 的标题改一下");

            assertThat(QueryTerms.mentionsFile(terms, "src/views/login-page.vue"))
                    .as("用户写驼峰、文件用连字符是常态，不该因此漏掉加成")
                    .isTrue();
        }

        @Test
        @DisplayName("点名了一个 .vue 文件，不能让同工程的其它 .vue 文件也算被点名")
        void extensionWordDoesNotNameEveryVueFile() {
            Set<String> terms = QueryTerms.extract("把 Navbar.vue 里那个导航项改成景区导览");

            assertThat(QueryTerms.mentionsFile(terms, "src/components/Navbar.vue")).isTrue();
            assertThat(QueryTerms.mentionsFile(terms, "src/pages/Attractions.vue"))
                    .as("首轮实测正是栽在这里：vue 命中每一条 .vue 路径，于是全工程 8 个 .vue 文件"
                            + "各拿 +0.5，点名加成退化成「按扩展名批发」，点名型 100% 的召回因此不可信")
                    .isFalse();
        }

        @Test
        @DisplayName("点名 ProductCard.vue 不应把 products.js 也当成被点名")
        void partialNameDoesNotLeakToSiblingFile() {
            Set<String> terms = QueryTerms.extract("ProductCard.vue 上那个「缺货」的提示改成灰色小字");

            assertThat(QueryTerms.mentionsFile(terms, "src/components/ProductCard.vue")).isTrue();
            assertThat(QueryTerms.mentionsFile(terms, "src/data/products.js"))
                    .as("驼峰拆出来的 product 是「路径子串匹配」时代的漏洞，改成主干相等后不再误伤")
                    .isFalse();
        }

        @Test
        @DisplayName("目录名不是文件名 —— 光说 views 并没有点出要改哪个文件")
        void directoryNameIsNotAMention() {
            assertThat(QueryTerms.mentionsFile(Set.of("views"), "src/views/Login.vue"))
                    .as("旧实现按「术语出现在路径里」判定，把所有目录同名文件一并算作点名")
                    .isFalse();
        }

        @Test
        @DisplayName("无关术语不应误判为点名")
        void unrelatedIsFalse() {
            assertThat(QueryTerms.mentionsFile(Set.of("判断"), "src/views/Login.vue")).isFalse();
        }

        @Test
        @DisplayName("过短的术语不参与点名，否则两个字母会命中一大堆文件")
        void shortTermIgnored() {
            assertThat(QueryTerms.mentionsFile(Set.of("ab"), "src/ab.vue")).isFalse();
        }

        @Test
        @DisplayName("空输入应安全返回 false")
        void emptyInput() {
            assertThat(QueryTerms.mentionsFile(Set.of(), "a.vue")).isFalse();
            assertThat(QueryTerms.mentionsFile(Set.of("login"), null)).isFalse();
            assertThat(QueryTerms.mentionsFile(Set.of("login"), "")).isFalse();
        }
    }

    @Nested
    @DisplayName("横切文件识别与折扣")
    class GenericFileCase {

        @Test
        @DisplayName("全局样式文件应判为横切 —— 它影响整站，跟任何界面需求都「有点关系」")
        void styleFilesAreGeneric() {
            assertThat(QueryTerms.isGenericFile("src/styles/main.css")).isTrue();
            assertThat(QueryTerms.isGenericFile("src/assets/theme.scss")).isTrue();
        }

        @Test
        @DisplayName("框架约定入口名应判为横切，但判据必须带上扩展名")
        void frameworkEntryNamesAreGeneric() {
            assertThat(QueryTerms.isGenericFile("src/main.js")).isTrue();
            assertThat(QueryTerms.isGenericFile("src/App.vue")).isTrue();
            assertThat(QueryTerms.isGenericFile("index.html")).isTrue();
            assertThat(QueryTerms.isGenericFile("src/router/index.js")).isTrue();

            assertThat(QueryTerms.isGenericFile("docs/index.md"))
                    .as("index.md 只是同名、不是入口；不限定扩展名就等于按文件名批发")
                    .isFalse();
        }

        @Test
        @DisplayName("业务组件与数据文件不该判为横切 —— 它们正是要被召回的期望文件")
        void businessFilesAreNotGeneric() {
            assertThat(QueryTerms.isGenericFile("src/components/Navbar.vue")).isFalse();
            assertThat(QueryTerms.isGenericFile("src/pages/Guide.vue")).isFalse();
            assertThat(QueryTerms.isGenericFile("src/data/products.js")).isFalse();
            assertThat(QueryTerms.isGenericFile(null)).isFalse();
            assertThat(QueryTerms.isGenericFile("")).isFalse();
        }

        @Test
        @DisplayName("默认系数 1.0 时一律不打折，非法值也按关闭处理")
        void disabledByDefault() {
            assertThat(QueryTerms.genericFilePenalty(Set.of("字体"), "src/styles/main.css", 1.0D))
                    .as("装了不等于用了：默认值必须是行为与改造前完全一致的那个")
                    .isEqualTo(1.0D);
            assertThat(QueryTerms.genericFilePenalty(Set.of("字体"), "src/styles/main.css", 0D))
                    .isEqualTo(1.0D);
            assertThat(QueryTerms.genericFilePenalty(Set.of("字体"), "src/styles/main.css", -0.5D))
                    .isEqualTo(1.0D);
            assertThat(QueryTerms.genericFilePenalty(Set.of("字体"), "src/styles/main.css", Double.NaN))
                    .isEqualTo(1.0D);
            assertThat(QueryTerms.genericFilePenalty(Set.of("字体"), "src/styles/main.css", 1.2D))
                    .isEqualTo(1.0D);
        }

        @Test
        @DisplayName("开启后只打折横切文件，业务文件不受影响")
        void appliesOnlyToGenericFiles() {
            assertThat(QueryTerms.genericFilePenalty(Set.of("版权"), "src/styles/main.css", 0.9D))
                    .isEqualTo(0.9D);
            assertThat(QueryTerms.genericFilePenalty(Set.of("版权"), "src/pages/About.vue", 0.9D))
                    .as("About.vue 既不是框架入口也不是样式 —— 它抢榜首是向量区分度的问题，不该用文件先验去压")
                    .isEqualTo(1.0D);
        }

        @Test
        @DisplayName("需求点名了横切文件时必须豁免")
        void mentionedFileIsExempt() {
            Set<String> terms = QueryTerms.extract("把 main.css 里的默认字体换掉");

            assertThat(QueryTerms.genericFilePenalty(terms, "src/styles/main.css", 0.9D))
                    .as("折扣会牺牲「要改全局文件却没说文件名」的需求，点名是唯一的豁免信号；"
                            + "漏掉它等于用户已经说清了改哪个文件，反而被系统压下去")
                    .isEqualTo(1.0D);
        }
    }

    @Nested
    @DisplayName("代码标识符抽取")
    class SymbolsOfCase {

        @Test
        @DisplayName("应抽出标识符并剔除语言关键字")
        void extractsIdentifiers() {
            String symbols = QueryTerms.symbolsOf("const loginButton = ref(1)");

            assertThat(symbols).contains("loginbutton", "ref");
            assertThat(symbols).doesNotContain("const");
        }

        @Test
        @DisplayName("应保留中文字面量 —— 用户说「登录」，代码里就有「登录」")
        void keepsChineseLiterals() {
            assertThat(QueryTerms.symbolsOf("  <h1>登录</h1>")).contains("登录");
        }

        @Test
        @DisplayName("重复标识符只保留一次")
        void deduplicates() {
            String symbols = QueryTerms.symbolsOf("loginButton loginButton loginButton");

            assertThat(symbols.split(",")).containsOnlyOnce("loginbutton");
        }

        @Test
        @DisplayName("长度必须受控，否则会撑爆 symbols 字段（VARCHAR(512)）")
        void lengthIsBounded() {
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < 400; i++) {
                code.append("identifierNumber").append(i).append(" ");
            }
            assertThat(QueryTerms.symbolsOf(code.toString()).length()).isLessThanOrEqualTo(512);
        }

        @Test
        @DisplayName("空白输入返回空串")
        void blankInput() {
            assertThat(QueryTerms.symbolsOf(null)).isEmpty();
            assertThat(QueryTerms.symbolsOf("   ")).isEmpty();
        }
    }
}
