package com.quan.aicodehelper.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 多模型降级配置。
 *
 * <p><b>解决什么问题</b>：单一模型配置下，模型服务的任何一次抖动都直接等于
 * 用户看到失败 —— 限流、5xx、网络超时都会让整次生成白费（而且已经烧掉的
 * token 不会退）。更麻烦的是额度耗尽：所有请求会稳定失败，直到人工换 key。</p>
 *
 * <p><b>怎么做</b>：配一条有序模型链，主模型不可用时自动切到下一个。
 * 每个候选可以只换模型名（同名不同档位，如 qwen-plus → qwen-turbo），
 * 也可以换整套 base-url + key（应对单 key 额度耗尽 —— 这正是开发者
 * 最常遇到的场景）。</p>
 *
 * <p><b>为什么是「有序链」而不是「主 + 一个备用」</b>：两级的表达力太弱。
 * 实际需要往往是「同一家的便宜档位 → 另一家的兜底」，这至少是三级；
 * 而且把「换 key」表达成链上的一个节点，比单独开一套 key 轮换逻辑简单得多。</p>
 *
 * <p><b>为什么不配 candidates 就等于不降级</b>：默认链为空时行为与改造前
 * 完全一致，不引入任何新失败面。降级能力是「显式开启」的。</p>
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "app.model-fallback")
public class ModelFallbackProperties {

    /**
     * 总开关。关闭时只用主模型，行为与改造前一致。
     */
    private boolean enabled = true;

    /**
     * 有序的备用模型候选列表，按顺序尝试。
     *
     * <p>为空表示没有备用模型 —— 此时降级能力形同关闭（会打一条 INFO 说明）。</p>
     */
    private List<Candidate> candidates = new ArrayList<>();

    /**
     * 单个候选（含主模型）的最大尝试次数。
     *
     * <p>1 表示不做同模型内重试，直接切下一个候选。
     * 设为 2 时，同一个模型会连续试两次再往下切 ——
     * 应对「偶发网络抖动」有效，但会让真正故障时的等待时间翻倍。</p>
     */
    private int attemptsPerCandidate = 1;

    /**
     * 是否把降级过程推送给前端。
     *
     * <p>用户有权知道「这次结果是备用模型给的」—— 备用模型往往档位更低，
     * 输出质量可能有差异。静默降级会让用户困惑于「为什么这次生成得不一样」。</p>
     */
    private boolean notifyUser = true;

    @PostConstruct
    public void init() {
        if (attemptsPerCandidate <= 0) {
            log.warn("attemptsPerCandidate 非法（{}），已回退为 1", attemptsPerCandidate);
            attemptsPerCandidate = 1;
        }
        if (!enabled) {
            log.info("多模型降级已关闭（app.model-fallback.enabled=false）");
            return;
        }
        if (candidates.isEmpty()) {
            log.info("多模型降级已启用，但未配置候选模型（app.model-fallback.candidates 为空），"
                    + "等同于不降级");
            return;
        }
        int usable = (int) candidates.stream().filter(Candidate::isUsable).count();
        if (usable < candidates.size()) {
            log.warn("有 {} 个候选模型配置不完整（缺 model-name 或 api-key），将被跳过",
                    candidates.size() - usable);
        }
        log.info("多模型降级已启用：{} 个候选（每个最多试 {} 次），顺序={}",
                usable, attemptsPerCandidate,
                candidates.stream().filter(Candidate::isUsable)
                        .map(Candidate::getModelName).toList());
    }

    /**
     * 一个备用模型候选。
     *
     * <p>字段全部可选：只填 model-name 表示「同一套凭证换模型」；
     * 填了 api-key / base-url 则覆盖主模型配置，用于跨账号降级。</p>
     */
    @Data
    public static class Candidate {

        /**
         * 模型名。为空则该候选不可用。
         */
        private String modelName;

        /**
         * 覆盖 base-url，为空则沿用主模型配置。
         */
        private String baseUrl;

        /**
         * 覆盖 api-key，为空则沿用主模型配置。
         *
         * <p>用于「主 key 额度耗尽 → 切到备用 key」这种最常见的降级场景。</p>
         */
        private String apiKey;

        /**
         * 该候选的输出上限，为空或非正数则沿用主模型配置。
         *
         * <p>备用模型档位通常更低，上限给太大没有意义。</p>
         */
        private Integer maxTokens;

        /**
         * 仅用于日志与前端提示的可读名称，为空时回退为 modelName。
         */
        private String label;

        /**
         * 候选是否可用：至少要有模型名。
         */
        public boolean isUsable() {
            return modelName != null && !modelName.isBlank();
        }

        /**
         * 展示名。
         */
        public String displayName() {
            return (label != null && !label.isBlank()) ? label : modelName;
        }
    }
}
