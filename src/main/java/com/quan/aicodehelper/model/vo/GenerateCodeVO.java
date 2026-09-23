package com.quan.aicodehelper.model.vo;

import com.quan.aicodehelper.core.stream.GenerationTask;
import com.quan.aicodehelper.core.stream.GenerationTaskStatusEnum;
import lombok.Data;

/**
 * 发起生成后的受理回执。前端据此知道请求已被受理、该去订阅哪个应用的进度。
 * 它刻意不返回事件流：回执一旦返回，生成本身就不再依赖这条连接。
 */
@Data
public class GenerateCodeVO {

    /**
     * 应用 ID，订阅进度时原样回传。
     */
    private Long appId;

    /**
     * 任务当前状态。若命中「已有进行中任务」的复用路径，前端应理解为接续了刚才那次生成，而非新发起一次。
     */
    private String status;

    private String statusDescription;

    /**
     * 是否复用了已有任务。前端据此提示「检测到该应用正在生成，已为你接续」。
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
