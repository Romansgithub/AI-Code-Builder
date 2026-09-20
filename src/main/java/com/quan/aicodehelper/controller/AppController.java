package com.quan.aicodehelper.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.quan.aicodehelper.ai.tools.GenerationProgressRegistry;
import com.quan.aicodehelper.annotation.AuthCheck;
import com.quan.aicodehelper.common.BaseResponse;
import com.quan.aicodehelper.common.DeleteRequest;
import com.quan.aicodehelper.common.ResultUtils;
import com.quan.aicodehelper.constant.AppConstant;
import com.quan.aicodehelper.constant.UserConstant;
import com.quan.aicodehelper.core.stream.GenerationTask;
import com.quan.aicodehelper.exception.BusinessException;
import com.quan.aicodehelper.exception.ErrorCode;
import com.quan.aicodehelper.exception.ThrowUtils;
import com.quan.aicodehelper.model.dto.app.*;
import com.quan.aicodehelper.model.entity.App;
import com.quan.aicodehelper.model.entity.User;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import com.quan.aicodehelper.model.vo.AppVO;
import com.quan.aicodehelper.model.vo.GenerateCodeStatusVO;
import com.quan.aicodehelper.model.vo.GenerateCodeVO;
import com.quan.aicodehelper.service.AppService;
import com.quan.aicodehelper.service.ProjectDownloadService;
import com.quan.aicodehelper.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import org.springframework.http.codec.ServerSentEvent;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 应用 控制层。
 *
 * @author <a href="https://github.com/romansgithub">Roman</a>
 */
@Slf4j
@RestController
@RequestMapping("/app")
public class AppController {

    @Resource
    private AppService appService;

    @Resource
    private UserService userService;

    @Resource
    private GenerationProgressRegistry generationProgressRegistry;
    @Autowired
    private ProjectDownloadService projectDownloadService;


    /**
     * 创建应用（用户）
     *
     * @param appAddRequest 创建请求
     * @return 新应用 id
     */
    @PostMapping("/add")
    public BaseResponse<Long> addApp(@RequestBody AppAddRequest appAddRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appAddRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        Long appId = appService.createApp(appAddRequest, loginUser);
        return ResultUtils.success(appId);
    }

    /**
     * 更新应用（用户只能更新自己的应用名称）
     *
     * @param appUpdateRequest 更新请求
     * @param request          请求
     * @return 更新结果
     */
    @PostMapping("/update")
    public BaseResponse<Boolean> updateApp(@RequestBody AppUpdateRequest appUpdateRequest, HttpServletRequest request) {
        if (appUpdateRequest == null || appUpdateRequest.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        long id = appUpdateRequest.getId();
        // 判断是否存在
        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人可更新
        if (!oldApp.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        App app = new App();
        app.setId(id);
        app.setAppName(appUpdateRequest.getAppName());
        // 设置编辑时间
        app.setEditTime(LocalDateTime.now());
        boolean result = appService.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }


    /**
     * 删除自己的应用（用户）
     *
     * @param deleteRequest 删除请求
     * @return 是否成功
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteApp(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null, ErrorCode.PARAMS_ERROR);
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        // 校验应用是否存在且属于当前用户
        App app = appService.getById(deleteRequest.getId());
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!app.getUserId().equals(loginUser.getId()) && !UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole()), ErrorCode.NO_AUTH_ERROR, "无权删除该应用");
        boolean result = appService.removeById(deleteRequest.getId());
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 查看应用详情（用户）
     *
     * @param id 应用 id
     * @return 应用详情
     */
    @GetMapping("/get/vo")
    public BaseResponse<AppVO> getAppVOById(long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        App app = appService.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 检查权限：普通用户只能查看自己的应用，管理员可以查看所有应用
        if (!UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole())) {
            ThrowUtils.throwIf(!app.getUserId().equals(loginUser.getId()), ErrorCode.NO_AUTH_ERROR, "无权查看该应用");
        }
        return ResultUtils.success(appService.getAppVO(app));
    }

    /**
     * 分页获取当前用户创建的应用列表
     *
     * @param appQueryRequest 查询请求
     * @param request         请求
     * @return 应用列表
     */
    @PostMapping("/my/list/page/vo")
    public BaseResponse<Page<AppVO>> listMyAppVOByPage(@RequestBody AppQueryRequest appQueryRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        // 限制每页最多 20 个
        long pageSize = appQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize > 20, ErrorCode.PARAMS_ERROR, "每页最多查询 20 个应用");
        long pageNum = appQueryRequest.getPageNum();
        // 只查询当前用户的应用
        appQueryRequest.setUserId(loginUser.getId());
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        // 数据封装
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        List<AppVO> appVOList = appService.getAppVOList(appPage.getRecords());
        appVOPage.setRecords(appVOList);
        return ResultUtils.success(appVOPage);
    }


    /**
     * 分页获取精选应用列表
     *
     * @param appQueryRequest 查询请求
     * @return 精选应用列表
     */
    @PostMapping("/good/list/page/vo")
    public BaseResponse<Page<AppVO>> listGoodAppVOByPage(@RequestBody AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        // 限制每页最多 20 个
        long pageSize = appQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize > 20, ErrorCode.PARAMS_ERROR, "每页最多查询 20 个应用");
        long pageNum = appQueryRequest.getPageNum();
        // 只查询精选的应用
        appQueryRequest.setPriority(AppConstant.GOOD_APP_PRIORITY);
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        // 分页查询
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        // 数据封装
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        List<AppVO> appVOList = appService.getAppVOList(appPage.getRecords());
        appVOPage.setRecords(appVOList);
        return ResultUtils.success(appVOPage);
    }


    // ==================== 管理员接口 ====================

    /**
     * 管理员删除应用
     *
     * @param deleteRequest 删除请求
     * @return 是否成功
     */
    @PostMapping("/admin/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteAppByAdmin(@RequestBody DeleteRequest deleteRequest) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null, ErrorCode.PARAMS_ERROR);
        App app = appService.getById(deleteRequest.getId());
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        boolean result = appService.removeById(deleteRequest.getId());
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(result);
    }

    /**
     * 管理员更新应用（支持更新名称、封面、优先级）
     *
     * @param appAdminUpdateRequest 管理员更新请求
     * @return 是否成功
     */
    @PostMapping("/admin/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateAppAdmin(@RequestBody AppAdminUpdateRequest appAdminUpdateRequest) {
        ThrowUtils.throwIf(appAdminUpdateRequest == null || appAdminUpdateRequest.getId() == null, ErrorCode.PARAMS_ERROR);
        App app = appService.getById(appAdminUpdateRequest.getId());
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        App updateApp = new App();
        BeanUtil.copyProperties(appAdminUpdateRequest, updateApp);
        updateApp.setEditTime(LocalDateTime.now());
        boolean result = appService.updateById(updateApp);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 管理员分页查询应用列表（支持除时间外的任何字段查询，每页数量不限）
     *
     * @param appQueryRequest 查询请求
     * @return 分页结果
     */
    @PostMapping("/admin/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<AppVO>> listAppVOPageByAdmin(@RequestBody AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long pageNum = appQueryRequest.getPageNum();
        long pageSize = appQueryRequest.getPageSize();
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        List<AppVO> appVOList = appService.getAppVOList(appPage.getRecords());
        appVOPage.setRecords(appVOList);
        return ResultUtils.success(appVOPage);
    }

    /**
     * 管理员查看应用详情
     *
     * @param id 应用 id
     * @return 应用详情
     */
    @GetMapping("/admin/get/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<AppVO> getAppVOByIdAdmin(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        App app = appService.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        return ResultUtils.success(appService.getAppVO(app));
    }

    /**
     * 发起代码生成（写操作）。
     *
     * <p><b>为什么从 GET 改成 POST</b>：这个端点会真实触发代码生成 ——
     * 写对话记录、消耗模型 token、落盘文件、可能跑 npm 构建。
     * 把它放在 GET 上，等于允许「浏览器自动重放的请求」去做写操作：
     * 网络一抖，EventSource 就会带着同一个 URL 自动重连，
     * 服务端老老实实再生成一遍。这是重复生成事故的直接来源。</p>
     *
     * <p>POST 之后浏览器不会自动重放（fetch 不会自动重试非幂等请求），
     * 且消息体可以放在 body 里，不再受 URL 长度限制。</p>
     *
     * <p><b>响应是普通 JSON 而不是流</b>：本端点只负责「把任务启动起来」并立刻返回。
     * 进度与内容由 {@code /chat/gen/stream} 独立订阅，
     * 这样连接断开不会波及生成，重连也不会重新启动生成。</p>
     */
    @PostMapping("/chat/gen/code")
    public BaseResponse<GenerateCodeVO> generateCode(@RequestBody GenerateCodeRequest generateCodeRequest,
                                                    HttpServletRequest request) {
        ThrowUtils.throwIf(generateCodeRequest == null, ErrorCode.PARAMS_ERROR);
        Long appId = generateCodeRequest.getAppId();
        String message = generateCodeRequest.getMessage();
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "消息不能为空");

        // 记下请求进来的时刻：service 返回的任务若创建时间早于它，
        // 说明本次请求没有真正启动生成，而是复用了本来就存在的任务
        long requestStartedAt = System.currentTimeMillis();

        // 权限校验与任务抢占都在 service 层：已有进行中任务时不报错，而是交回既有任务，
        // 这正是「重连不重启生成」的落点。
        User loginUser = userService.getLoginUser(request);
        GenerationTask task = appService.startGeneration(appId, message, loginUser);
        GenerateCodeVO vo = GenerateCodeVO.of(appId, task);
        // 复用已有任务时明确告知前端：它接的是「刚才那次生成」，不是新起了一次
        vo.setReused(!task.isRunning() || task.getCreatedAt() < requestStartedAt);
        return ResultUtils.success(vo);
    }

    /**
     * 订阅代码生成进度（读操作，可安全重放）。
     *
     * <p>与触发端点的分工是这次改造的核心：读接口重复执行只是「多听一遍」，
     * 写接口重复执行才是事故。拆开之后，即便代理、浏览器或用户手工重试这个 GET，
     * 代价也仅仅是多一条订阅连接。</p>
     *
     * <p><b>断点续传</b>：前端通过 {@code Last-Event-ID} 头（或 query 参数）告知
     * 自己最后收到的事件序号，服务端从事件缓冲里补齐错过的部分再接着推。
     * 用请求头而非 query 是遵循 SSE 规范 —— 浏览器原生重连会自动带上这个头。</p>
     */
    @GetMapping(value = "/chat/gen/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> subscribeGenStream(@RequestParam Long appId,
                                                           @RequestParam(required = false) Long lastEventId,
                                                           HttpServletRequest request) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        try {
            User loginUser = userService.getLoginUser(request);
            appService.checkAppPermission(appId, loginUser);
        } catch (BusinessException e) {
            log.warn("订阅生成进度被拒绝：{}", e.getMessage());
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("business-error")
                    .data(JSONUtil.toJsonStr(Map.of("message", e.getMessage())))
                    .build());
        }

        long resumeFrom = resolveLastEventId(request, lastEventId);
        return generationProgressRegistry.subscribe(appId, resumeFrom);
    }

    /**
     * 查询生成任务状态（供刷新页面后恢复现场）。
     */
    @GetMapping("/chat/gen/status")
    public BaseResponse<GenerateCodeStatusVO> getGenStatus(@RequestParam Long appId, HttpServletRequest request) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        User loginUser = userService.getLoginUser(request);
        appService.checkAppPermission(appId, loginUser);
        return ResultUtils.success(GenerateCodeStatusVO.of(appId, generationProgressRegistry.getTask(appId)));
    }

    /**
     * 解析续传起点。
     *
     * <p>优先取 {@code Last-Event-ID} 请求头：这是 SSE 规范定义的字段，
     * 浏览器原生重连会自动携带，遵循它意味着将来换回原生 EventSource 也能续传。
     * query 参数只作为显式指定的补充。</p>
     */
    private long resolveLastEventId(HttpServletRequest request, Long queryValue) {
        String header = request.getHeader("Last-Event-ID");
        if (StrUtil.isNotBlank(header)) {
            try {
                return Long.parseLong(header.trim());
            } catch (NumberFormatException ignored) {
                log.warn("非法的 Last-Event-ID 请求头：{}", header);
            }
        }
        return queryValue == null || queryValue < 0 ? 0L : queryValue;
    }

    /**
     * 部署应用
     * @param appDeployRequest
     * @param request
     * @return
     */
    @PostMapping("/deploy")
    public BaseResponse<String> deployApp(@RequestBody AppDeployRequest appDeployRequest, HttpServletRequest request)
    {
        ThrowUtils.throwIf(appDeployRequest == null, ErrorCode.PARAMS_ERROR);
        Long appId = appDeployRequest.getAppId();
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        User loginUser = userService.getLoginUser(request);
        String deployUrl = appService.deployApp(appId, loginUser);
        return ResultUtils.success(deployUrl);
    }

    /**
     * 下载应用代码
     * @param appId
     * @param request
     * @param response
     */
    @GetMapping("/download/{appId}")
    public void downloadAppCode(@PathVariable Long appId, HttpServletRequest request, HttpServletResponse response){
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        User loginUer = userService.getLoginUser(request);
        if(!app.getUserId().equals(loginUer.getId())){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问");
        }
        String codeGenType = app.getCodeGenType();
        String sourceDirName = codeGenType + "_" + appId;
        String sourceDirPath = AppConstant.getCodeOutputRootDir() + File.separator + sourceDirName;
        File sourceDir = new File(sourceDirPath);
        ThrowUtils.throwIf(!sourceDir.exists() || !sourceDir.isDirectory(), ErrorCode.NOT_FOUND_ERROR, "应用代码不存在");
        String downloadFileName = String.valueOf(appId);
        projectDownloadService.downloadProjectAsZip(sourceDirPath, downloadFileName, response);

    }
}
