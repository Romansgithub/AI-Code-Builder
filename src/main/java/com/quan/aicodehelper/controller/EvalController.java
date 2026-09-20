package com.quan.aicodehelper.controller;

import com.quan.aicodehelper.annotation.AuthCheck;
import com.quan.aicodehelper.common.BaseResponse;
import com.quan.aicodehelper.common.ResultUtils;
import com.quan.aicodehelper.constant.UserConstant;
import com.quan.aicodehelper.core.eval.EvalRunResult;
import com.quan.aicodehelper.core.eval.EvalRunner;
import com.quan.aicodehelper.core.eval.EvalSetLoader;
import com.quan.aicodehelper.model.dto.eval.EvalSet;
import com.quan.aicodehelper.model.vo.EvalReportVO;
import com.quan.aicodehelper.service.impl.EvalReportService;
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
 * 评测 控制层。
 *
 * <p>整组接口都限管理员：跑批要烧 token 并执行 npm 构建，报告含平台质量数据，
 * 都不是普通用户该碰的。</p>
 *
 * <p><b>为什么跑批是 POST 而不是同步 GET</b>：一轮跑批涉及多次 AI 调用与
 * npm 构建，耗时以十分钟计，远超前端与网关的请求超时。这里仍是同步实现
 * （调用方需长超时），但它是个「手动触发、等结果」的运维动作，
 * 不属于用户交互路径，因此不做异步任务化。</p>
 */
@Slf4j
@RestController
@RequestMapping("/eval")
@Tag(name = "评测接口")
public class EvalController {

    @Resource
    private EvalRunner evalRunner;

    @Resource
    private EvalSetLoader evalSetLoader;

    @Resource
    private EvalReportService evalReportService;

    /**
     * 查看当前评测集内容。
     *
     * <p>跑批前先看用例是必要的 —— 否则跑完才发现评测集里的 prompt 写错了。</p>
     */
    @GetMapping("/set")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @Operation(summary = "查看评测集")
    public BaseResponse<EvalSet> getEvalSet() {
        return ResultUtils.success(evalSetLoader.load());
    }

    /**
     * 触发一轮评测跑批。
     *
     * <p>同步返回结果并附上可读报告。注意这会真实调用 AI、真实跑 npm，
     * 耗时很长，请用足够长的超时调用。</p>
     */
    @PostMapping("/run")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @Operation(summary = "执行评测跑批（耗时较长）")
    public BaseResponse<EvalRunResult> runEval() {
        log.info("收到评测跑批请求");
        EvalRunResult result = evalRunner.run();
        // 同时把可读报告打进日志：跑批通常由命令行/接口触发，
        // 直接看日志比再调一次报告接口方便
        log.info("评测跑批报告：\n{}", result.toReadableReport());
        return ResultUtils.success(result);
    }

    /**
     * 查询评测报告（基于已落库的历史数据聚合）。
     *
     * @param sinceDays 统计最近多少天，不传表示全量
     * @param batchId   指定批次，不传表示跨批次汇总
     */
    @GetMapping("/report")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @Operation(summary = "查询评测报告")
    public BaseResponse<EvalReportVO> getReport(
            @RequestParam(required = false) Integer sinceDays,
            @RequestParam(required = false) String batchId) {
        return ResultUtils.success(evalReportService.buildReport(sinceDays, batchId));
    }
}
