package com.quan.aicodehelper.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.quan.aicodehelper.model.dto.app.AppAddRequest;
import com.quan.aicodehelper.model.dto.app.AppQueryRequest;
import com.quan.aicodehelper.model.entity.App;
import com.quan.aicodehelper.model.entity.User;
import com.quan.aicodehelper.model.vo.AppVO;
import com.quan.aicodehelper.core.stream.GenerationTask;

import java.util.List;

/**
 * 应用 服务层。
 *
 * @author <a href="https://github.com/romansgithub">Roman</a>
 */
public interface AppService extends IService<App> {

    /** 按查询请求拼查询条件。 */
    QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest);

    /**
     * 发起一次代码生成，返回任务现场。
     *
     * <p>返回任务而非事件流：流的存活依赖本次连接，断线生成就没了，重连还会重跑一遍；返回任务现场则生成
     * 由独立线程跑到头，连接只是订阅者。已有进行中任务时直接返回那个任务，不抛错也不重跑。</p>
     */
    GenerationTask startGeneration(Long appId, String message, User loginUser);

    /**
     * 校验用户是否有权访问该应用。订阅进度、查询状态这类读操作必须显式调用它，
     * 漏掉就成了知道 appId 就能围观别人的生成过程。
     */
    void checkAppPermission(Long appId, User loginUser);

    Long createApp(AppAddRequest appAddRequest, User loginUser);

    /** 部署应用，返回可访问的部署地址。 */
    String deployApp(Long appId, User loginUser);

    void generateAppScreenshotAsync(Long appId, String appDeployUrl);

    boolean removeById(Long appId);

    /** 转为应用视图对象。 */
    AppVO getAppVO(App app);

    /** 批量转为应用视图对象。 */
    List<AppVO> getAppVOList(List<App> appList);
}
