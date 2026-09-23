package com.quan.aicodehelper.model.vo;

import com.quan.aicodehelper.core.stream.GenerationTask;
import com.quan.aicodehelper.core.stream.GenerationTaskStatusEnum;
import lombok.Data;

/**
 * 生成任务状态查询结果，用于「页面整个被刷新、前端连 lastEventId 都没有」的场景：
 * 刷新后只能问一句这个应用在不在生成、上次结果如何，否则只能靠用户手动点一下试探、容易踩出第二次生成。
 */
@Data
public class GenerateCodeStatusVO {

    private Long appId;

    /**
     * 是否存在任务现场，含刚结束、尚在保留期内的任务。
     */
    private boolean hasTask;

    /**
     * 任务状态值；无任务时为 null。
     */
    private String status;

    private String statusDescription;

    /**
     * 生成是否仍在进行中，前端据此决定「订阅进度」还是「直接刷新预览」。
     */
    private boolean running;

    /**
     * 失败原因，仅在状态为 failed 时有值。
     */
    private String failureReason;

    public static GenerateCodeStatusVO of(Long appId, GenerationTask task) {
        GenerateCodeStatusVO vo = new GenerateCodeStatusVO();
        vo.setAppId(appId);
        if (task == null) {
            vo.setHasTask(false);
            vo.setRunning(false);
            return vo;
        }
        GenerationTaskStatusEnum status = task.getStatus();
        vo.setHasTask(true);
        vo.setStatus(status.getValue());
        vo.setStatusDescription(status.getDescription());
        vo.setRunning(status == GenerationTaskStatusEnum.RUNNING);
        vo.setFailureReason(task.getFailureReason());
        return vo;
    }
}
