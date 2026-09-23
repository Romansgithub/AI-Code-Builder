package com.quan.aicodehelper.core.rag.eval;

import com.quan.aicodehelper.model.enums.RetrievalKind;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 检索召回评测结果。三组权重（仅关键词/仅向量/混合）同批跑才有对照，工程、索引、查询向量完全一致，
 * 差异只来自权重，这是消融实验成立的前提；点名型与语义型必须分开汇总，混算会让点名型的高分掩盖语义型的短板。
 */
@Data
public class RagEvalResult {

    private String setName;
    private String setVersion;
    private int totalCases;
    private int topK;
    private int maxChunksPerFile;

    /** 本次运行生效的全局样式折扣系数（1.0 = 关闭）。必须打进报告头：报告自证配置，跨批次才能直接比，详见 docs/design-notes.md 第五节。 */
    private double genericFilePenalty = 1.0D;

    /** embedding 是否可用；为 false 时指标退化为纯关键词，与消融的「纯关键词」组混读会误判向量没用。 */
    private boolean embeddingAvailable;

    private long indexCostMs;

    private List<AppIndexStat> apps = new ArrayList<>();

    /** 按权重配置分组的结果，顺序即执行顺序。 */
    private List<ModeResult> modes = new ArrayList<>();

    private long totalCostMs;

    /** 单个被测应用的索引概况。片段数为 0 时所有指标必然为 0，先看这里能省掉一轮排查。 */
    @Data
    public static class AppIndexStat {
        private String key;
        private String label;
        private long appId;
        private long chunks;
    }

    @Data
    public static class ModeResult {
        private String modeName;
        private double vectorWeight;
        private double keywordWeight;
        private MetricSummary overall;
        private MetricSummary pointed;
        private MetricSummary semantic;

        /**
         * 排名第一的片段向量分大于 0 的用例数，只是「链路通不通」的下限检查，不能证明向量有用（向量可用时必然等于用例总数）；该值为 0 说明向量分对排序毫无影响，且仅在 vectorWeight 大于 0 时才有意义。
         */
        private int vectorSignalCases;

        /**
         * top-1 片段的关键词分为 0 的用例数，是「向量真在起作用」的强证据：榜首若不出自关键词命中，便只可能来自向量；只有两组权重都大于 0 时才有区分力。
         */
        private int keywordSilentCases;

        private List<CaseOutcome> cases = new ArrayList<>();
    }

    /** 一组指标汇总，全部按文件级计算：用户说「改一下导航栏」，指向的是一个文件而非某几行。 */
    @Data
    public static class MetricSummary {
        private int cases;

        private int hits;

        /** 期望文件排在首位的用例数。MRR 把第 1 名与第 3 名揉成一个平均数，Top-1 是可直接对照的条数，两者一起看才既能排序又能定位。 */
        private int topOneHits;

        private double hitRate;
        private double recallAtK;
        private double precisionAtK;
        private double mrr;
    }

    @Data
    public static class CaseOutcome {
        private String caseId;
        private String appKey;
        private RetrievalKind kind;
        private String query;
        private List<String> expectedFiles = new ArrayList<>();

        /** 检索结果里出现过的文件，按排名去重后的顺序。 */
        private List<String> retrievedFiles = new ArrayList<>();
        private int retrievedCount;

        /** 第一个命中期望文件的排名（从 1 开始）；0 表示全部落空。 */
        private int firstHitRank;

        private double recall;
        private double precision;
        private double reciprocalRank;

        /** 排名第一的片段得分构成，未命中时用它定位「是向量没召回还是关键词没召回」。 */
        private double topVectorScore;
        private double topKeywordScore;
        private long costMs;
    }

    /** 渲染成可直接读的报告；跑评测通常由接口或命令行触发，结论直接打进日志比让人再调一次接口省事。 */
    public String toReadableReport() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("\n┌─ 代码检索召回评测 ────────────────────────────────────────────\n");
        sb.append("│ 评测集：").append(setName).append(' ').append(setVersion)
                .append("，用例 ").append(totalCases).append(" 条\n");
        sb.append("│ 参数：topK=").append(topK)
                .append("，单文件片段上限=").append(maxChunksPerFile)
                .append("，通用文件折扣=").append(penaltyLabel()).append('\n');
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
                // 「仅关键词」组权重为 0、向量分不进总分；照常打印会让人误以为向量参与了排序，而这恰是唯一没参与的一组
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
                    // 「仅向量」组榜首全部由向量决定，只印其中几条会暗示其余是关键词顶上来的，与事实相反
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

        // 这里补的是「捞到了但排在后面」：按占位文件汇总而非列用例号，要修的是那个文件（通常是又长又泛的全局样式/首页），而不是这批用例。
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

    /** 折扣系数在报告里的显示形式；关闭时显式写成 {@code 1.0（关闭）} 而非只印 {@code 1.0}，报告必须自证配置。 */
    private String penaltyLabel() {
        return genericFilePenalty >= 1.0D
                ? String.format("%.1f（关闭）", genericFilePenalty)
                : String.format("%.2f", genericFilePenalty);
    }

    private static String pct(double value) {
        return String.format("%.1f%%", value * 100);
    }

    /** 分组对照表的一行。Top-1 同时给出百分比与条数：百分比便于横向比较，条数便于回明细里对上号。 */
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
