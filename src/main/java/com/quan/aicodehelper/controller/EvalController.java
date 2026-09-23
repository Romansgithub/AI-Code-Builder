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
 * 评测 控制层（限管理员：跑批烧 token 并跑 npm 构建，报告含平台质量数据）。
 *
 * <p>跑批用 POST 同步实现：一轮耗时以十分钟计，远超请求超时；它是手动触发、等结果的运维动作，不做异步任务化。</p>
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
     * 查看当前评测集内容。跑批前应先验用例，避免跑完才发现 prompt 写错。
     */
    @GetMapping("/set")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @Operation(summary = "查看评测集")
    public BaseResponse<EvalSet> getEvalSet() {
        return ResultUtils.success(evalSetLoader.load());
    }

    /**
     * 触发一轮评测跑批，同步返回结果并附可读报告；会真实调用 AI 与 npm，需用足够长的超时调用。
     * 支持 caseId 是因为跑批真实烧 token：只改一条链路时传 {@code ?caseId=xxx} 只验那一条，不必付整集成本。
     *
     * @param caseId 只跑这条用例，不传为全量；传不存在的 id 会直接报错，不会静默跑 0 条
     */
    @PostMapping("/run")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @Operation(summary = "执行评测跑批（耗时较长）")
    public BaseResponse<EvalRunResult> runEval(
            @RequestParam(required = false) String caseId) {
        log.info("收到评测跑批请求，caseId={}", caseId == null ? "全量" : caseId);
        EvalRunResult result = evalRunner.run(caseId);
        // 跑批多由命令行或接口触发，报告直接进日志比再调一次报告接口方便
        log.info("评测跑批报告：\n{}", result.toReadableReport());
        return ResultUtils.success(result);
    }

    /**
     * 查询评测报告（基于已落库的历史数据聚合）。
     *
     * @param sinceDays 统计最近多少天，不传为全量；batchId 指定批次，不传为跨批次汇总
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
