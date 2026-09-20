package com.quan.aicodehelper.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.quan.aicodehelper.ai.config.AiCodeGenTypeRoutingConfig;
import com.quan.aicodehelper.ai.config.RoutingAiModelConfig;
import com.quan.aicodehelper.ai.service.AiCodeGenTypeRoutingService;
import com.quan.aicodehelper.constant.AppConstant;
import com.quan.aicodehelper.config.StorageProperties;
import com.quan.aicodehelper.core.builder.VueProjectBuilder;
import com.quan.aicodehelper.exception.BusinessException;
import com.quan.aicodehelper.exception.ErrorCode;
import com.quan.aicodehelper.exception.ThrowUtils;
import com.quan.aicodehelper.model.dto.app.AppAddRequest;
import com.quan.aicodehelper.model.dto.app.AppQueryRequest;
import com.quan.aicodehelper.model.entity.App;
import com.quan.aicodehelper.mapper.AppMapper;
import com.quan.aicodehelper.model.entity.User;
import com.quan.aicodehelper.model.enums.ChatHistoryMessageTypeEnum;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import com.quan.aicodehelper.model.vo.AppVO;
import com.quan.aicodehelper.model.vo.UserVO;
import com.quan.aicodehelper.service.AppService;
import com.quan.aicodehelper.service.ChatHistoryService;
import com.quan.aicodehelper.service.UserService;
import com.quan.aicodehelper.ai.facade.AiCodeGeneratorFacade;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 应用 服务层实现。
 *
 * @author <a href="https://github.com/romansgithub">Roman</a>
 */
@Slf4j
@Service
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFactory;

    @Resource
    private UserService userService;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private VueProjectBuilder vueProjectBuilder;

    @Resource
    private ScreenshotServiceImpl screenshotServiceImpl;
    @Resource
    private AiCodeGenTypeRoutingConfig aiCodeGenTypeRoutingConfig;

    /**
     * 存储与部署配置。部署访问地址由它统一拼接，避免 host 硬编码漏端口 / 漏 context-path。
     */
    @Resource
    private StorageProperties storageProperties;

    @Override
    public QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest) {
        if (appQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = appQueryRequest.getId();
        String appName = appQueryRequest.getAppName();
        String cover = appQueryRequest.getCover();
        String initPrompt = appQueryRequest.getInitPrompt();
        String codeGenType = appQueryRequest.getCodeGenType();
        String deployKey = appQueryRequest.getDeployKey();
        Integer priority = appQueryRequest.getPriority();
        Long userId = appQueryRequest.getUserId();
        String sortField = appQueryRequest.getSortField();
        String sortOrder = appQueryRequest.getSortOrder();
        return QueryWrapper.create()
                .eq("id", id)
                .like("appName", appName)
                .like("cover", cover)
                .like("initPrompt", initPrompt)
                .eq("codeGenType", codeGenType)
                .eq("deployKey", deployKey)
                .eq("priority", priority)
                .eq("userId", userId)
                .orderBy(sortField, "ascend".equals(sortOrder));
    }


    /**
     * 根据用户请求生成代码
     * @param appId
     * @param message
     * @param loginUser
     * @return
     */

    @Override
    public Flux<String> chatToGenCode(Long appId, String message, User loginUser) {

        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "消息不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.PARAMS_ERROR, "用户不存在");
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        if (!app.getUserId().equals(loginUser.getId()))
        {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权访问该应用");
        }
        String codeGenTypeStr = app.getCodeGenType();
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenTypeStr);
        if (codeGenTypeEnum == null)
        {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "无效的代码生成类型");
        }

        chatHistoryService.addChatMessage(appId, message, ChatHistoryMessageTypeEnum.USER.getValue(), loginUser.getId());
        Flux<String> contentFlux = aiCodeGeneratorFactory.generateCodeStream(message, codeGenTypeEnum, appId);
        StringBuilder aiResponseBuilder = new StringBuilder();

        return contentFlux
                .map(chunk -> {
                    aiResponseBuilder.append(chunk);
                    return chunk;
                })
                // 说明：以下回调运行在响应式线程中，异步于本方法的调用栈，
                // 因此不能用 @Transactional 包住整个方法 —— 事务会在流完成前就提交。
                // 这里每次落库都是独立的单条 insert，本身即原子操作，无需外层事务。
                .doOnComplete(() -> {
                    String aiResponse = aiResponseBuilder.toString();
                    if (StrUtil.isNotBlank(aiResponse)){
                        safeSaveChatMessage(appId, aiResponse, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                    }
                })
                .doOnError(error -> {
                    // 仅记录日志，不把异常信息当 AI 回复写入对话历史 ——
                    // 否则用户刷新页面会看到一条以「AI回复失败」开头的伪消息，
                    // 且该消息会被回填进模型记忆，污染后续对话上下文。
                    log.error("应用 {} 生成代码失败：{}", appId, error.getMessage(), error);
                });
    }

    /**
     * 安全落库对话消息：失败仅记录日志，不影响主流程。
     *
     * <p>落库发生在响应式流的完成回调中，此时主请求早已返回，
     * 任何异常都无法反馈给前端，只能自行吞掉并记录，避免线程内异常扩散。</p>
     */
    private void safeSaveChatMessage(Long appId, String message, String messageType, Long userId) {
        try {
            chatHistoryService.addChatMessage(appId, message, messageType, userId);
        } catch (Exception e) {
            log.error("应用 {} 的 {} 消息落库失败：{}", appId, messageType, e.getMessage());
        }
    }

    /**
     * 创建应用
     * @param appAddRequest
     * @param loginUser
     * @return
     */
    @Override
    public Long createApp(AppAddRequest appAddRequest, User loginUser)
    {
        String initPrompt = appAddRequest.getInitPrompt();
        ThrowUtils.throwIf(StrUtil.isBlank(initPrompt), ErrorCode.PARAMS_ERROR, "初始化提示不能为空");
        App app = new App();
        BeanUtil.copyProperties(appAddRequest, app);
        app.setUserId(loginUser.getId());
        app.setAppName(initPrompt.substring(0, Math.min(initPrompt.length(), 12)));
        AiCodeGenTypeRoutingService routingService = aiCodeGenTypeRoutingConfig.aiCodeGenTypeRoutingService();
        CodeGenTypeEnum selectedCodeGenType = routingService.routeCodeGenType(initPrompt);
        app.setCodeGenType(selectedCodeGenType.getValue());
        boolean result = this.save(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "创建应用失败");
        log.info("创建应用成功，应用ID：{}, 类型：{}", app.getId(), app.getCodeGenType());
        return app.getId();
    }

    /**
     * 部署应用
     * @param appId
     * @param loginUser
     * @return
     */
    @Override
    public String deployApp(Long appId, User loginUser)
    {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(loginUser == null,ErrorCode.NOT_LOGIN_ERROR,"用户未登录");
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        if(!app.getUserId().equals(loginUser.getId())){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权访问该应用");
        }
        String deployKey = app.getDeployKey();
        if (StrUtil.isBlank(deployKey))
        {
            deployKey = RandomUtil.randomString(6);
        }
        String codeGenType = app.getCodeGenType();
        String sourceDirName = codeGenType + "_" + appId;
        String sourceDirPath = AppConstant.getCodeOutputRootDir() + File.separator + sourceDirName;
        File sourceDir = new File(sourceDirPath);
        if (!sourceDir.exists() || !sourceDir.isDirectory()){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "应用代码不存在，请先生成代码");
        }
        boolean isVueProject = CodeGenTypeEnum.VUE_PROJECT.getValue().equals(codeGenType);
        if (isVueProject)
        {
            boolean buildSuccess = vueProjectBuilder.buildProject(sourceDirPath);
            ThrowUtils.throwIf(!buildSuccess, ErrorCode.SYSTEM_ERROR,"Vue项目构建失败，请检查构建日志");
        }
        String deployDirPath = AppConstant.getCodeDeployRootDir() + File.separator + deployKey;
        try{
            File deploySourceDir = isVueProject ? new File(sourceDirPath, "dist") : sourceDir;
            if (isVueProject && !new File(deploySourceDir, "index.html").exists())
            {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "构建产物 dist 不存在，请先完成 Vue 项目构建");
            }
            // 先清空部署目录再拷贝：copyContent 是「按文件覆盖」，源目录里已删除的文件
            // 会残留在部署目录中（如改名后的旧 chunk），表现为「重新部署了但页面还是旧的」。
            File deployTarget = new File(deployDirPath);
            FileUtil.clean(deployTarget);
            FileUtil.copyContent(deploySourceDir, deployTarget, true);
        }catch (Exception e)
        {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "部署失败:" + e.getMessage());
        }

        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setDeployKey(deployKey);
        updateApp.setDeployedTime(java.time.LocalDateTime.now());
        boolean result = this.updateById(updateApp);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "更新应用部署信息失败");
        // 部署访问地址统一由 StorageProperties 拼接，始终带尾斜杠 —— 预览页 index.html 用的是
        // 相对路径 "./assets/xxx"，缺少尾斜杠时浏览器会把 deployKey 当文件名，静态资源全部 404。
        String appDeployUrl = storageProperties.buildDeployUrl(deployKey);
        generateAppScreenshotAsync(appId, appDeployUrl);
        return appDeployUrl;
    }

    /**
     * 异步生成应用截图并更新封面
     * @param appId
     * @param appDeployUrl
     */
    @Override
    public void generateAppScreenshotAsync(Long appId, String appDeployUrl) {
        Thread.startVirtualThread(() ->{
            try {
                String screenshotUrl = screenshotServiceImpl.generateAndUploadScreenshot(appDeployUrl);
                if (StrUtil.isBlank(screenshotUrl)) {
                    log.warn("应用 {} 截图生成为空，跳过封面更新", appId);
                    return;
                }
                App updateApp = new App();
                updateApp.setId(appId);
                updateApp.setCover(screenshotUrl);
                boolean updated = this.updateById(updateApp);
                if (!updated) {
                    log.warn("应用 {} 封面更新失败", appId);
                }
            } catch (Exception e) {
                // 该方法运行在独立虚拟线程中，异常若抛出将无人接收（仅打印到控制台），
                // 因此必须在此处兜底捕获，避免静默失败
                log.error("应用 {} 截图生成或封面更新异常：{}", appId, e.getMessage(), e);
            }
        });
    }

    /**
     * 删除应用
     * @param appId
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Long appId)
    {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        }
        // 先删对话历史、再删应用：两者必须同成同败，
        // 否则中途失败会残留指向已不存在应用的孤儿对话记录。
        // 注意执行顺序 —— 若先删应用，一旦后续失败则应用已消失而历史仍在，
        // 数据更难修复；先清理从数据再删主记录，失败时整体回滚代价最小。
        chatHistoryService.deleteByAppId(appId);
        boolean removed = super.removeById(appId);
        if (!removed) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "删除应用失败");
        }
        return true;
    }

    /**
     * 获取应用VO
     * @param app
     * @return
     */
    @Override
    public AppVO getAppVO(App app)
    {
        if (app == null)
        {
            return null;
        }
        AppVO appVO = new AppVO();
        BeanUtil.copyProperties(app, appVO);
        Long userId = app.getUserId();
        if (userId != null){
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            appVO.setUser(userVO);
        }
        return appVO;
    }

    /**
     * 获取应用VO列表
     * @param appList
     * @return
     */
    @Override
    public List<AppVO> getAppVOList(List<App> appList) {
        if (CollUtil.isEmpty(appList)) {
            return new ArrayList<>();
        }
        // 批量获取用户信息，避免 N+1 查询问题
        Set<Long> userIds = appList.stream()
                .map(App::getUserId)
                .collect(Collectors.toSet());
        Map<Long, UserVO> userVOMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, userService::getUserVO));
        return appList.stream().map(app -> {
            AppVO appVO = getAppVO(app);
            UserVO userVO = userVOMap.get(app.getUserId());
            appVO.setUser(userVO);
            return appVO;
        }).collect(Collectors.toList());
    }


}
