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

    /**
     * 获取查询条件
     *
     * @param appQueryRequest
     * @return
     */
    QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest);

    /**
     * 发起一次代码生成。
     *
     * <p><b>为什么返回任务而不是事件流</b>：返回流意味着「生成的存活依赖本次调用的连接」，
     * 连接一断生成就没了，用户重连还会把生成重跑一遍。
     * 返回任务现场则把两者拆开 —— 生成由独立线程跑到头，
     * 连接只是订阅者，来去都不影响结果。</p>
     *
     * <p>若该应用已有进行中的任务，直接返回那个任务的现场（调用方转为订阅），
     * 而不是抛错或再启动一次生成。</p>
     *
     * @param appId     应用 ID
     * @param message   用户消息
     * @param loginUser 当前登录用户
     * @return 任务现场
     */
    GenerationTask startGeneration(Long appId, String message, User loginUser);

    /**
     * 校验用户是否有权访问该应用。
     *
     * <p>订阅进度与查询状态这类「读」操作需要它：它们不像生成那样自带业务校验，
     * 若漏掉就会变成「知道 appId 就能围观别人生成过程」。</p>
     *
     * @param appId     应用 ID
     * @param loginUser 当前登录用户
     */
    void checkAppPermission(Long appId, User loginUser);

    Long createApp(AppAddRequest appAddRequest, User loginUser);

    /**
     * 部署应用
     * @param appId
     * @param loginUser
     * @return
     */
    String deployApp(Long appId, User loginUser);

    void generateAppScreenshotAsync(Long appId, String appDeployUrl);

    boolean removeById(Long appId);

    /**
     * 获取应用VO
     * @param app
     * @return
     */
    AppVO getAppVO(App app);

    /**
     * 获取应用VO列表
     * @param appList
     * @return
     */
    List<AppVO> getAppVOList(List<App> appList);
}
