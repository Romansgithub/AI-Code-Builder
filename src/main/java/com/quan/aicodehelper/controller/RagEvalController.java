package com.quan.aicodehelper.controller;

import com.quan.aicodehelper.annotation.AuthCheck;
import com.quan.aicodehelper.common.BaseResponse;
import com.quan.aicodehelper.common.ResultUtils;
import com.quan.aicodehelper.constant.UserConstant;
import com.quan.aicodehelper.core.rag.eval.RagEvalResult;
import com.quan.aicodehelper.core.rag.eval.RagEvalRunner;
import com.quan.aicodehelper.core.rag.eval.RagEvalSetLoader;
import com.quan.aicodehelper.model.dto.rag.RagEvalSet;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检索召回评测 控制层。
 *
 * <p>与 {@link EvalController} 分开而不是加在它下面：两者度量的是不同东西
 * （生成质量 vs 检索质量），生命周期也不同 —— 生成评测会真实调用 AI 并跑 npm 构建，
 * 检索评测只走索引与向量化。混在一个控制器里，调用方很难从路径看出
 * 「这一下要花多少时间、会动到哪些数据」。</p>
 *
 * <p>同样限管理员：跑一轮全量消融会产生数十次 embedding 调用，属于烧额度的动作。</p>
 */
@Slf4j
@RestController
@RequestMapping("/rag-eval")
@Tag(name = "检索召回评测接口")
public class RagEvalController {

    @Resource
    private RagEvalRunner ragEvalRunner;

    @Resource
    private RagEvalSetLoader ragEvalSetLoader;

    /**
     * 查看当前检索评测集。
     *
     * <p>跑之前先看一眼用例是必要的 —— 尤其是期望文件的标注，
     * 标注写错的评测集跑出来的数字是错的，而错误的指标比没有指标更危险。</p>
     */
    @GetMapping("/set")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @Operation(summary = "查看检索评测集")
    public BaseResponse<RagEvalSet> getEvalSet() {
        return ResultUtils.success(ragEvalSetLoader.load());
    }

    /**
     * 触发一轮检索召回评测。
     *
     * <p>同步返回：一轮全量消融约 1 分钟内（索引首次建好后更快），
     * 仍在常规请求可接受的范围内，不做异步任务化。</p>
     *
     * @param ablation 是否跑三组权重消融，默认 true。每条用例检索一次就要一次
     *                 embedding 调用，三组即三倍开销；只想确认「有没有变差」时
     *                 传 {@code false} 只跑线上生效的那组权重
     */
    @PostMapping("/run")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @Operation(summary = "执行检索召回评测")
    public BaseResponse<RagEvalResult> run(
            @RequestParam(required = false, defaultValue = "true") boolean ablation) {
        log.info("收到检索召回评测请求，ablation={}", ablation);
        RagEvalResult result = ragEvalRunner.run(ablation);
        // 报告同时打进日志：跑评测通常由接口触发，直接看日志比再调一次接口方便
        log.info("检索召回评测报告：{}", result.toReadableReport());
        return ResultUtils.success(result);
    }
}
