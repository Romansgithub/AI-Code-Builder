package com.quan.aicodehelper.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.ZipUtil;
import com.quan.aicodehelper.exception.BusinessException;
import com.quan.aicodehelper.exception.ErrorCode;
import com.quan.aicodehelper.exception.ThrowUtils;
import com.quan.aicodehelper.service.ProjectDownloadService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileFilter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;

@Service
@Slf4j
public class ProjectDownloadServiceImpl implements ProjectDownloadService {

    /**
     * 忽略的文件名
     */
    private static final Set<String> IGNORED_NAMES = Set.of(
            "node_modules",
            ".git",
            "dist",
            "build",
            ".DS_Store",
            ".env",
            "target",
            ".mvn",
            ".idea",
            ".vscode"
    );

    /**
     * 忽略的文件扩展名
     */
    private static final Set<String> IGNORED_EXTENSIONS = Set.of(
            ".log",
            ".tmp",
            ".cache"
    );

    /**
     * 判断路径是否被允许
     * @param projectRoot
     * @param fullPath
     * @return
     */
    private boolean isPathAllowed(Path projectRoot, Path fullPath){
        Path relativePath = projectRoot.relativize(fullPath);
        for (Path path : relativePath)
        {
            String pathName = path.toString();
            if (IGNORED_NAMES.contains(pathName)
                    || IGNORED_EXTENSIONS.stream().anyMatch(pathName::endsWith))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * 下载项目为zip文件
     * @param projectPath
     * @param downloadFileName
     * @param response
     */
    @Override
    public void downloadProjectAsZip(String projectPath, String downloadFileName, HttpServletResponse response)
    {
        ThrowUtils.throwIf(StrUtil.isBlank(projectPath), ErrorCode.PARAMS_ERROR, "项目路径不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(downloadFileName), ErrorCode.PARAMS_ERROR, "下载文件名不能为空");
        File projectDir = new File(projectPath);
        ThrowUtils.throwIf(!projectDir.exists(), ErrorCode.PARAMS_ERROR, "项目路径不存在");
        ThrowUtils.throwIf(!projectDir.isDirectory(), ErrorCode.PARAMS_ERROR, "项目路径不是一个目录");
        log.info("开始下载项目：{} -> {}.zip", projectPath,downloadFileName);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/zip");
        response.addHeader("Content-Disposition", String.format("attachment;filename=\"%s.zip\"", downloadFileName));
        FileFilter fileFilter = file -> isPathAllowed(projectDir.toPath(), file.toPath());
        try{
            ZipUtil.zip(response.getOutputStream(), StandardCharsets.UTF_8, false, fileFilter, projectDir);
            log.info("项目打包下载完成：{}",downloadFileName);

        } catch (Exception e) {
            log.error("项目打包下载失败：{}", downloadFileName, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "项目打包下载失败");
        }
    }
}
