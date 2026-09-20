package com.quan.aicodehelper.model.vo;

import com.quan.aicodehelper.core.stream.GenerationTask;
import com.quan.aicodehelper.core.stream.GenerationTaskStatusEnum;
import lombok.Data;

/**
 * 发起生成后的受理回执。
 *
 * <p>前端拿到它就知道「这次请求已被受理」以及「现在该去订阅哪个应用的进度」。
 * 它刻意不返回事件流 —— 回执一旦返回，生成本身就不再依赖这条连接了。</p>
 */
@Data
public class GenerateCodeVO {

    /**
     * 应用 ID，订阅进度时原样回传。
     */
    private Long appId;

    /**
     * 任务当前状态。
     *
     * <p>大概率是 {@code running}；但若命中「已有进行中任务」的复用路径，
     * 前端应理解为「你连的是刚才那次生成」，而不是「我这次发起了新的生成」。</p>
     */
    private String status;

    /**
     * 状态的中文描述，便于前端直接展示。
     */
    private String statusDescription;

    /**
     * 是否复用了已有任务。
     *
     * <p>区分它有意义：前端可以据此提示「检测到该应用正在生成，已为你接续」，
     * 而不是让用户以为点了一下又跑了一遍。</p>
     */
    private boolean reused;

    public static GenerateCodeVO of(Long appId, GenerationTask task) {
        GenerateCodeVO vo = new GenerateCodeVO();
        vo.setAppId(appId);
        GenerationTaskStatusEnum status = task == null
                ? GenerationTaskStatusEnum.FAILED
                : task.getStatus();
        vo.setStatus(status.getValue());
        vo.setStatusDescription(status.getDescription());
        return vo;
    }
}
