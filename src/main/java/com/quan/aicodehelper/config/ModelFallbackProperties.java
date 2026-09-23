package com.quan.aicodehelper.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 多模型降级配置。主模型限流、5xx、超时或额度耗尽时，按顺序切到下一个候选模型。
 * 候选既可只换模型名（同名不同档位），也可换整套 base-url + key（应对单 key 额度耗尽）。
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
     * 有序的备用模型候选列表，按顺序尝试。为空表示没有备用模型，降级能力形同关闭。
     */
    private List<Candidate> candidates = new ArrayList<>();

    /**
     * 单个候选（含主模型）的最大尝试次数。1 表示不做同模型内重试、直接切下一个；
     * 设为 2 应对偶发抖动有效，但让真正故障时的等待时间翻倍。
     */
    private int attemptsPerCandidate = 1;

    /**
     * 是否把降级过程推送给前端。备用模型档位往往更低，静默降级会让用户困惑于结果为何不一样。
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
     * 一个备用模型候选，字段全部可选：只填 model-name 表示同一套凭证换模型，
     * 填了 api-key / base-url 则覆盖主模型配置，用于跨账号降级。
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
         * 覆盖 api-key，为空则沿用主模型配置。用于「主 key 额度耗尽 → 切到备用 key」。
         */
        private String apiKey;

        /**
         * 该候选的输出上限，为空或非正数则沿用主模型配置。
         */
        private Integer maxTokens;

        /**
         * 仅用于日志与前端提示的可读名称，为空时回退为 modelName。
         */
        private String label;

        public boolean isUsable() {
            return modelName != null && !modelName.isBlank();
        }

        public String displayName() {
            return (label != null && !label.isBlank()) ? label : modelName;
        }
    }
}
