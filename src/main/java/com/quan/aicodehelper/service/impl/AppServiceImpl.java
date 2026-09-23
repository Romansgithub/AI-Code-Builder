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
import com.quan.aicodehelper.ai.tools.GenerationProgressRegistry;
import com.quan.aicodehelper.core.budget.LlmCallGuard;
import com.quan.aicodehelper.core.builder.BuildResult;
import com.quan.aicodehelper.core.healing.BuildSelfHealingService;
import com.quan.aicodehelper.core.stream.GenerationTask;
import com.quan.aicodehelper.core.stream.GenerationTaskExecutor;
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

    /** 任务现场登记处，生成任务的唯一性由它保证（见 {@link #startGeneration}）。 */
    @Resource
    private GenerationProgressRegistry generationProgressRegistry;

    /** 任务执行器：把生成搬到独立虚拟线程，与 HTTP 连接的生命周期脱钩。 */
    @Resource
    private GenerationTaskExecutor generationTaskExecutor;

    @Resource
    private UserService userService;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private BuildSelfHealingService buildSelfHealingService;

    @Resource
    private ScreenshotServiceImpl screenshotServiceImpl;
    @Resource
    private AiCodeGenTypeRoutingConfig aiCodeGenTypeRoutingConfig;

    /** 存储与部署配置，部署访问地址由它统一拼接，避免 host 硬编码漏端口或 context-path。 */
    @Resource
    private StorageProperties storageProperties;

    /** LLM 调用预算闸门：类型路由这条路径不经过 Facade，需在此单独过闸。 */
    @Resource
    private LlmCallGuard llmCallGuard;

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


    @Override
    public GenerationTask startGeneration(Long appId, String message, User loginUser) {

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

        // 抢占任务是防重复生成的唯一防线：并发请求里只有一个能拿到任务，其余拿到 null 后转为订阅既有任务
        GenerationTask task = generationProgressRegistry.startTask(appId);
        if (task == null) {
            GenerationTask running = generationProgressRegistry.getTask(appId);
            // 正在跑时不报错，交回既有任务让调用方订阅，这就是重连的语义
            if (running != null && running.isRunning()) {
                log.info("应用 {} 已有进行中的生成任务，本次请求转为订阅", appId);
                return running;
            }
            // 抢占失败又不在运行中，是被并发请求抢先了，同样交回现场
            ThrowUtils.throwIf(running == null, ErrorCode.SYSTEM_ERROR, "生成任务启动失败，请重试");
            return running;
        }

        // 抢到任务现场才说明这次真要跑生成。此处只做不消耗额度的可行性检查（真正记账在 Facade），
        // 且必须放在抢占之后：提前会把只订阅的请求也计费；不检查则留下「已注册未启动」的 RUNNING 任务挡住应用。
        try {
            llmCallGuard.assertAvailable("代码生成");
        } catch (RuntimeException e) {
            generationProgressRegistry.finishTask(appId, e.getMessage());
            throw e;
        }

        // 用户消息先落库：它代表真实输入，与生成成败无关，生成失败时刷新页面仍能看到自己发过什么
        chatHistoryService.addChatMessage(appId, message, ChatHistoryMessageTypeEnum.USER.getValue(), loginUser.getId());

        Flux<String> contentFlux = aiCodeGeneratorFactory.generateCodeStream(message, codeGenTypeEnum, appId);
        StringBuilder aiResponseBuilder = new StringBuilder();

        Flux<String> tracked = contentFlux
                .map(chunk -> {
                    aiResponseBuilder.append(chunk);
                    return chunk;
                })
                // 回调跑在响应式线程里，异步于本次调用栈，@Transactional 会在流完成前就提交，故不能包住整个方法；
                // 此处每次落库都是独立的单条 insert，本身即原子操作，无需外层事务。
                .doOnComplete(() -> {
                    String aiResponse = aiResponseBuilder.toString();
                    if (StrUtil.isNotBlank(aiResponse)){
                        safeSaveChatMessage(appId, aiResponse, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                    }
                })
                .doOnError(error -> {
                    // 只记日志，不把异常写进对话历史：否则用户刷新后会看到伪消息，
                    // 且该消息会被回填进模型记忆，污染后续对话上下文。
                    log.error("应用 {} 生成代码失败：{}", appId, error.getMessage(), error);
                });

        // 生成放到独立虚拟线程里跑到头，与 HTTP 连接解耦：断线不中断生成，重连可从事件缓冲补齐进度
        generationTaskExecutor.start(task, tracked);
        return task;
    }

    @Override
    public void checkAppPermission(Long appId, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权访问该应用");
        }
    }

    /**
     * 安全落库对话消息，失败只记日志。落库发生在流的完成回调里，主请求早已返回，
     * 异常无从反馈给前端，只能自行吞掉，避免扩散。
     */
    private void safeSaveChatMessage(Long appId, String message, String messageType, Long userId) {
        try {
            chatHistoryService.addChatMessage(appId, message, messageType, userId);
        } catch (Exception e) {
            log.error("应用 {} 的 {} 消息落库失败：{}", appId, messageType, e.getMessage());
        }
    }

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
        // 类型路由是独立于代码生成的一条 LLM 调用路径，不经过 Facade，必须单独过闸，否则这里是漏账口子
        llmCallGuard.acquire("类型路由");
        CodeGenTypeEnum selectedCodeGenType = routingService.routeCodeGenType(initPrompt);
        app.setCodeGenType(selectedCodeGenType.getValue());
        boolean result = this.save(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "创建应用失败");
        log.info("创建应用成功，应用ID：{}, 类型：{}", app.getId(), app.getCodeGenType());
        return app.getId();
    }

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
            // 走带自愈的构建：部署是用户主动触发且要等结果的场景，构建失败先让 AI 试着修比直接抛错更有价值
            BuildResult buildResult = buildSelfHealingService.buildWithHealing(sourceDirPath, appId);
            ThrowUtils.throwIf(!buildResult.isSuccess(), ErrorCode.SYSTEM_ERROR,
                    "Vue 项目构建失败：" + StrUtil.blankToDefault(buildResult.getErrorMessage(), "请检查构建日志"));
        }
        String deployDirPath = AppConstant.getCodeDeployRootDir() + File.separator + deployKey;
        try{
            File deploySourceDir = isVueProject ? new File(sourceDirPath, "dist") : sourceDir;
            if (isVueProject && !new File(deploySourceDir, "index.html").exists())
            {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "构建产物 dist 不存在，请先完成 Vue 项目构建");
            }
            // 必须先清空部署目录：copyContent 是按文件覆盖，源目录里已删除的文件会残留（如改名后的旧 chunk），
            // 表现为「重新部署了但页面还是旧的」。
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
        // 访问地址统一由 StorageProperties 拼接并带尾斜杠：预览页用相对路径 "./assets/xxx"，
        // 缺尾斜杠时浏览器会把 deployKey 当文件名，静态资源全部 404。
        String appDeployUrl = storageProperties.buildDeployUrl(deployKey);
        generateAppScreenshotAsync(appId, appDeployUrl);
        return appDeployUrl;
    }

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
                // 该方法跑在独立虚拟线程里，异常抛出无人接收，必须在此兜底，否则静默失败
                log.error("应用 {} 截图生成或封面更新异常：{}", appId, e.getMessage(), e);
            }
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Long appId)
    {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        }
        // 先删对话历史再删应用，两者必须同成同败：否则会残留指向已不存在应用的孤儿记录。
        // 顺序也不能反：先删应用一旦后续失败，应用已消失而历史仍在，数据更难修复。
        chatHistoryService.deleteByAppId(appId);
        boolean removed = super.removeById(appId);
        if (!removed) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "删除应用失败");
        }
        return true;
    }

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

    @Override
    public List<AppVO> getAppVOList(List<App> appList) {
        if (CollUtil.isEmpty(appList)) {
            return new ArrayList<>();
        }
        // 批量取用户信息，避免 N+1 查询
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
