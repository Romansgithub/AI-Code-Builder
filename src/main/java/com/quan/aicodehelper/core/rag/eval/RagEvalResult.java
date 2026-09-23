package com.quan.aicodehelper.core.rag.eval;

import com.quan.aicodehelper.model.enums.RetrievalKind;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 检索召回评测结果。
 *
 * <p><b>为什么要把三种权重配置的结果放在同一份结果里</b>：这个评测的核心问题不是
 * 「召回率是多少」，而是「<b>为什么权重取 0.7 / 0.3</b>」。单独跑一组配置只能得到
 * 一个孤立的数字，无法回答任何「为什么」；三组同批跑出来才有对照，
 * 而且同批意味着工程、索引、查询向量完全一致，差异只来自权重 —— 这是消融实验成立的前提。</p>
 *
 * <p><b>为什么点名型与语义型必须分开汇总</b>：见 {@link RetrievalKind} 的说明。
 * 混在一起算平均会让点名型的高分掩盖语义型的短板。</p>
 */
@Data
public class RagEvalResult {

    private String setName;
    private String setVersion;
    private int totalCases;
    private int topK;
    private int maxChunksPerFile;

    /**
     * embedding 是否可用。为 false 时全部指标都不可信 ——
     * 检索会退化成纯关键词，而「纯关键词」那一组恰好也是本次消融的一组，
     * 两者会混为一谈，让人误以为「向量没用」。
     */
    private boolean embeddingAvailable;

    private long indexCostMs;

    private List<AppIndexStat> apps = new ArrayList<>();

    /**
     * 按权重配置分组的结果，顺序即执行顺序。
     */
    private List<ModeResult> modes = new ArrayList<>();

    private long totalCostMs;

    /**
     * 单个被测应用的索引概况。用来证明「索引确实建起来了」——
     * 片段数为 0 时所有指标必然为 0，先看这里能省掉一轮排查。
     */
    @Data
    public static class AppIndexStat {
        private String key;
        private String label;
        private long appId;
        private long chunks;
    }

    /**
     * 一种权重配置下的完整结果。
     */
    @Data
    public static class ModeResult {
        private String modeName;
        private double vectorWeight;
        private double keywordWeight;
        private MetricSummary overall;
        private MetricSummary pointed;
        private MetricSummary semantic;

        /**
         * 排名第一的片段向量分大于 0 的用例数（满分 = 用例总数）。
         *
         * <p>这只是「链路通不通」的下限检查：若该值为 0，说明向量分对排序毫无影响，
         * 此时「仅向量」与「仅关键词」两组的表现会异常接近，看数的人需要立刻意识到
         * 是链路问题而不是算法问题。</p>
         *
         * <p>但它<b>不能</b>证明向量有用 —— 只要向量可用，任意片段的余弦分都大于 0，
         * 这个数字必然等于用例总数，等于没测。真正的证据是
         * {@link #keywordSilentCases}。</p>
         *
         * <p><b>只有在 {@link #vectorWeight} 大于 0 的配置下才有意义。</b>
         * 「仅关键词」组里向量分照样算得出来（检索器会把两路分都带回来），
         * 只是权重为 0、不进总分 —— 照常展示这个数字会让人以为向量参与了排序，
         * 而它恰恰是唯一一组没参与的。</p>
         */
        private int vectorSignalCases;

        /**
         * top-1 片段的关键词分为 0 的用例数。
         *
         * <p>这才是「向量真的在起作用」的强证据：一条用例的首位如果不是关键词把它推上去的，
         * 那只可能是向量把它推上去的。反之若该值恒为 0，说明榜首全是靠字面命中拿下的，
         * 向量检索在这批数据上可以说不存在。</p>
         *
         * <p><b>只有两组权重都大于 0（即混合组）时才有区分力。</b>
         * 「仅向量」组里关键词权重为 0，16 条榜首<b>全部</b>由向量决定，
         * 此时统计出「4 条」反而会暗示其余 12 条是关键词顶上来的 —— 与事实相反。</p>
         */
        private int keywordSilentCases;

        private List<CaseOutcome> cases = new ArrayList<>();
    }

    /**
     * 一组指标汇总。全部按「文件级」计算 —— 需求落在哪个文件上，
     * 而不是落在哪几行上：用户说「改一下导航栏」，指向的是一个文件。
     */
    @Data
    public static class MetricSummary {
        private int cases;

        /**
         * 至少命中一个期望文件的用例数。
         */
        private int hits;

        /**
         * 期望文件排在首位的用例数。
         *
         * <p>MRR 是把「第 1 名」和「第 3 名」揉成一个平均数，看数的人很难一眼判断
         * 「到底是普遍靠后，还是只有几条拖后腿」；Top-1 是可直接对照的条数，
         * 两个一起看才既能排序又能定位。</p>
         */
        private int topOneHits;

        private double hitRate;
        private double recallAtK;
        private double precisionAtK;
        private double mrr;
    }

    /**
     * 单条用例的检索结果。
     */
    @Data
    public static class CaseOutcome {
        private String caseId;
        private String appKey;
        private RetrievalKind kind;
        private String query;
        private List<String> expectedFiles = new ArrayList<>();

        /**
         * 检索结果里出现过的文件（按排名去重后的顺序）。
         */
        private List<String> retrievedFiles = new ArrayList<>();
        private int retrievedCount;

        /**
         * 第一个命中期望文件的排名（从 1 开始）；0 表示全部落空。
         */
        private int firstHitRank;

        private double recall;
        private double precision;
        private double reciprocalRank;

        /**
         * 排名第一的片段得分构成，未命中时用它定位「是向量没召回还是关键词没召回」。
         */
        private double topVectorScore;
        private double topKeywordScore;
        private long costMs;
    }

    /**
     * 渲染成可直接读的报告。跑评测通常由接口或命令行触发，
     * 把结论直接打进日志比让人再调一次接口方便得多。
     */
    public String toReadableReport() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("\n┌─ 代码检索召回评测 ────────────────────────────────────────────\n");
        sb.append("│ 评测集：").append(setName).append(' ').append(setVersion)
                .append("，用例 ").append(totalCases).append(" 条\n");
        sb.append("│ 参数：topK=").append(topK)
                .append("，单文件片段上限=").append(maxChunksPerFile).append('\n');
        sb.append("│ 索引：");
        for (AppIndexStat app : apps) {
            sb.append(app.getLabel()).append("(").append(app.getChunks()).append(" 片段) ");
        }
        sb.append("，耗时 ").append(indexCostMs).append(" ms\n");
        sb.append("│ 向量：").append(embeddingAvailable
                        ? "可用" : "不可用 —— 指标已退化为纯关键词，不可作为向量效果的依据")
                .append('\n');
        sb.append("└───────────────────────────────────────────────────────────────\n");

        sb.append("\n【总体对照】同一批索引与查询，差异只来自权重\n");
        sb.append(String.format("  %-18s %-12s %-14s %-9s %-9s%n",
                "配置", "Recall@" + topK, "Precision@" + topK, "MRR", "命中率"));
        for (ModeResult mode : modes) {
            MetricSummary s = mode.getOverall();
            sb.append(String.format("  %-18s %-12s %-14s %-9s %-9s%n",
                    mode.getModeName(),
                    pct(s.getRecallAtK()), pct(s.getPrecisionAtK()),
                    String.format("%.3f", s.getMrr()), pct(s.getHitRate())));
        }
        sb.append("  ⚠ Precision@K 在「每条用例只有 1 个期望文件」时恒等于 1 ÷ 返回文件数，\n");
        sb.append("    衡量的是工程规模而非检索质量；同一时刻 命中率 也恒等于 Recall@K。\n");
        sb.append("    看排序质量请看 MRR 与 Top-1，看「有没有捞到」请看 Recall@K。\n");

        sb.append("\n【分组对照】点名型靠文件名即可命中，语义型才是向量该发力的地方\n");
        sb.append(String.format("  %-18s %-8s %-12s %-9s %-16s%n",
                "配置", "分组", "Recall@" + topK, "MRR", "Top-1"));
        for (ModeResult mode : modes) {
            sb.append(groupRow(mode.getModeName(), "点名型", mode.getPointed()));
            sb.append(groupRow(mode.getModeName(), "语义型", mode.getSemantic()));
        }

        sb.append("\n【向量是否真的参与打分】只在两组权重都非 0 的配置下才有意义\n");
        for (ModeResult mode : modes) {
            boolean vectorInPlay = mode.getVectorWeight() > 0D;
            boolean keywordInPlay = mode.getKeywordWeight() > 0D;
            if (!vectorInPlay) {
                // 「仅关键词」组里向量分照样算得出来，只是权重为 0、不进总分。
                // 照常印「16/16 条向量分 > 0」会让人以为向量参与了排序 ——
                // 而这恰恰是唯一一组没参与的
                sb.append(String.format(
                        "  %-18s 不适用：本组向量权重为 0，向量分未进总分，两行证据都无从谈起%n",
                        mode.getModeName()));
                continue;
            }
            sb.append(String.format(
                    "  %-18s 链路检查：%d/%d 条用例的 top-1 片段向量分 > 0（=0 说明向量根本没进排序）%n",
                    mode.getModeName(), mode.getVectorSignalCases(), totalCases));
            sb.append(keywordInPlay
                    ? String.format(
                    "  %-18s 强证据：%d/%d 条用例的 top-1 关键词分为 0 —— 这些用例的首位排名只能来自向量%n",
                    "", mode.getKeywordSilentCases(), totalCases)
                    // 「仅向量」组里 16 条榜首全部由向量决定，只印其中几条
                    // 会暗示其余的是关键词顶上来的 —— 与事实相反
                    : String.format(
                    "  %-18s 强证据：不适用：本组关键词权重为 0，榜首全部由向量决定，逐条统计无区分度%n",
                    ""));
        }

        sb.append("\n【漏召清单】用来定位「哪条用例、在哪组权重下捞不到期望文件」\n");
        for (ModeResult mode : modes) {
            List<String> missed = new ArrayList<>();
            for (CaseOutcome c : mode.getCases()) {
                if (c.getFirstHitRank() == 0) {
                    missed.add(c.getCaseId());
                }
            }
            sb.append(String.format("  %-18s 漏召 %d 条：%s%n",
                    mode.getModeName(), missed.size(),
                    missed.isEmpty() ? "无" : String.join("、", missed)));
        }

        // 漏召是「捞不到」，这里补的是「捞到了但排在后面」—— 后者更常见也更容易被忽略：
        // MRR 与 Top-1 只给出一个数，看不出榜首究竟被谁占走了。
        // 按「占位文件」汇总而不是列用例号，是因为要修的是那个文件（通常是又长又泛的
        // 全局样式 / 首页 / 关于页），而不是这批用例。
        sb.append("\n【排后清单】期望文件进了 Top-K 却没排首位：看榜首被谁占走 ——\n");
        sb.append("  若榜首长期是同一两个「对谁都像」的文件，那是通用文件淹没，该治的是它\n");
        for (ModeResult mode : modes) {
            Map<String, Integer> occupied = new LinkedHashMap<>();
            int late = 0;
            for (CaseOutcome c : mode.getCases()) {
                if (c.getFirstHitRank() > 1 && !c.getRetrievedFiles().isEmpty()) {
                    late++;
                    occupied.merge(c.getRetrievedFiles().get(0), 1, Integer::sum);
                }
            }
            if (late == 0) {
                sb.append(String.format("  %-18s 排后 0 条%n", mode.getModeName()));
                continue;
            }
            String detail = occupied.entrySet().stream()
                    .map(e -> e.getKey() + "×" + e.getValue())
                    .collect(Collectors.joining("、"));
            sb.append(String.format("  %-18s 排后 %d 条，榜首占位：%s%n",
                    mode.getModeName(), late, detail));
        }

        ModeResult main = modes.isEmpty() ? null : modes.get(modes.size() - 1);
        if (main != null) {
            sb.append("\n【逐条明细 · ").append(main.getModeName()).append("】\n");
            for (CaseOutcome c : main.getCases()) {
                boolean hit = c.getFirstHitRank() > 0;
                sb.append("  ").append(hit ? "[命中]" : "[漏召]").append(' ')
                        .append(c.getCaseId()).append("  ve=").append(fmt(c.getTopVectorScore()))
                        .append(" kw=").append(fmt(c.getTopKeywordScore())).append('\n');
                sb.append("         ").append(c.getQuery()).append('\n');
                sb.append("         期望：").append(String.join("、", c.getExpectedFiles())).append('\n');
                sb.append("         实得：").append(c.getRetrievedFiles().isEmpty()
                        ? "（空）" : String.join("、", c.getRetrievedFiles())).append('\n');
            }
        }
        return sb.toString();
    }

    private static String pct(double value) {
        return String.format("%.1f%%", value * 100);
    }

    /**
     * 分组对照表的一行。
     *
     * <p>Top-1 同时给出百分比与条数：百分比便于横向比较，
     * 条数便于回逐条明细里对上号 —— 语义型 62.5% 与「5/8」给人的行动感是不一样的。</p>
     */
    private static String groupRow(String modeName, String groupName, MetricSummary s) {
        return String.format("  %-18s %-8s %-12s %-9s %-16s%n",
                modeName, groupName,
                pct(s.getRecallAtK()),
                String.format("%.3f", s.getMrr()),
                pct(s.getCases() == 0 ? 0D : (double) s.getTopOneHits() / s.getCases())
                        + "（" + s.getTopOneHits() + "/" + s.getCases() + "）");
    }

    private static String fmt(double value) {
        return String.format("%.3f", value);
    }
}
