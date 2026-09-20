package com.quan.aicodehelper.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.quan.aicodehelper.model.dto.app.AppAddRequest;
import com.quan.aicodehelper.model.dto.app.AppQueryRequest;
import com.quan.aicodehelper.model.entity.App;
import com.quan.aicodehelper.model.entity.User;
import com.quan.aicodehelper.model.vo.AppVO;
import reactor.core.publisher.Flux;

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
     * 调用AI生成代码
     * @param appId
     * @param message
     * @param loginUer
     * @return
     */
    Flux<String> chatToGenCode(Long appId, String message, User loginUer);

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
