package com.quan.aicodehelper.controller;

import com.quan.aicodehelper.annotation.AuthCheck;
import com.quan.aicodehelper.common.BaseResponse;
import com.quan.aicodehelper.common.ResultUtils;
import com.quan.aicodehelper.constant.UserConstant;
import com.quan.aicodehelper.config.LlmBudgetProperties;
import com.quan.aicodehelper.core.budget.LlmCallGuard;
import com.quan.aicodehelper.model.vo.LlmBudgetVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LLM 调用预算 控制层（限管理员：反映平台资源消耗，重置影响全局）。
 */
@Slf4j
@RestController
@RequestMapping("/budget")
@Tag(name = "LLM 预算接口")
public class LlmBudgetController {

    @Resource
    private LlmCallGuard llmCallGuard;

    @Resource
    private LlmBudgetProperties budgetProperties;

    /**
     * 查看当前 LLM 调用用量。
     */
    @GetMapping("/usage")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @Operation(summary = "查看 LLM 调用用量")
    public BaseResponse<LlmBudgetVO> getUsage() {
        return ResultUtils.success(buildVO());
    }

    /**
     * 重置调用计数。为联调而存在：额度用完不必重启服务再重新登录；
     * 重置的是整个进程的计数，不只当前用户。
     */
    @PostMapping("/reset")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @Operation(summary = "重置 LLM 调用计数")
    public BaseResponse<LlmBudgetVO> reset() {
        log.warn("收到 LLM 预算重置请求，当前用量：{}", llmCallGuard.describeUsage());
        llmCallGuard.reset();
        return ResultUtils.success(buildVO());
    }

    private LlmBudgetVO buildVO() {
        long used = llmCallGuard.getUsedCalls();
        int max = budgetProperties.getMaxCalls();
        long remaining = llmCallGuard.getRemainingCalls();

        LlmBudgetVO vo = new LlmBudgetVO();
        vo.setEnabled(budgetProperties.isEnabled());
        vo.setMaxCalls(max);
        vo.setUsedCalls(used);
        vo.setRemainingCalls(remaining);
        vo.setRejectedCalls(llmCallGuard.getRejectedCalls());
        // max 已在配置侧兜底为正值，此处不会除零
        vo.setUsedPercent((int) Math.min(100L, used * 100L / max));
        vo.setExhausted(used >= max);
        vo.setRejectWhenExhausted(budgetProperties.isRejectWhenExhausted());
        vo.setSummary(llmCallGuard.describeUsage());
        return vo;
    }
}
