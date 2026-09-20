package com.quan.aicodehelper.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.quan.aicodehelper.exception.ErrorCode;
import com.quan.aicodehelper.exception.ThrowUtils;
import com.quan.aicodehelper.manager.CosManager;
import com.quan.aicodehelper.service.ScreenshotService;
import com.quan.aicodehelper.utils.WebScreenshotUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
@Slf4j
public class ScreenshotServiceImpl implements ScreenshotService {
    @Resource
    private CosManager cosManager;

    /**
     * 主功能函数
     * 生成并上传网页截图
     * @param webUrl
     * @return
     */
    @Override
    public String generateAndUploadScreenshot(String webUrl)
    {
        ThrowUtils.throwIf(StrUtil.isBlank(webUrl), ErrorCode.PARAMS_ERROR, "网页URL不能为空");
        log.info("开始生成网页截图：{}", webUrl);
        String localScreenshotPath = WebScreenshotUtils.saveWebPageScreenshot(webUrl);
        ThrowUtils.throwIf(StrUtil.isBlank(localScreenshotPath), ErrorCode.OPERATION_ERROR, "截图保存失败");
        try{
            String cosUrl = uploadScreenshotToCos(localScreenshotPath);
            ThrowUtils.throwIf(StrUtil.isBlank(cosUrl), ErrorCode.OPERATION_ERROR, "截图上传Cos失败");
            log.info("截图上传Cos成功：{}", cosUrl);
            return cosUrl;
        } finally {
            cleanupLocalFile(localScreenshotPath);
        }
    }

    /**
     * 删除本地截图文件夹
     * @param localScreenshotPath
     */
    private void cleanupLocalFile(String localScreenshotPath) {
        File localFile = new File(localScreenshotPath);
        if (localFile.exists())
        {
            File parentDir = localFile.getParentFile();
            FileUtil.del(parentDir);
            log.info("删除本地截图文件夹成功：{}", parentDir.getPath());
        }
    }

    /**
     * 上传截图到Cos
     * @param localScreenshotPath
     * @return
     */
    private String uploadScreenshotToCos(String localScreenshotPath) {
        if (StrUtil.isBlank(localScreenshotPath))
        {
            return null;
        }
        File screenshotFile = new File(localScreenshotPath);
        if (!screenshotFile.exists())
        {
            log.error("截图文件不存在：{}", localScreenshotPath);
            return null;
        }
        String fileName = UUID.randomUUID().toString().substring(0, 8) + "_compressed.jpg";
        String cosKey = generateScreenshotKey(fileName);
        return cosManager.uploadFile(cosKey, screenshotFile);
    }

    /**
     * 生成截图的Cos Key
     * @param fileName
     * @return
     */
    private String generateScreenshotKey(String fileName) {
        String dataPath = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        return String.format("/screenshots/%s%s", dataPath, fileName);
    }
}
