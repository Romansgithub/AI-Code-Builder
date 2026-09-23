package com.quan.aicodehelper.ai.saver;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.quan.aicodehelper.constant.AppConstant;
import com.quan.aicodehelper.exception.BusinessException;
import com.quan.aicodehelper.exception.ErrorCode;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;

import java.io.File;
import java.util.zip.Checksum;

public abstract class CodeFileSaverTemplate<T> {

    /**
     * 文件保存根目录。刻意不做成 {@code static final} 常量：路径由 StorageProperties 在启动时回填，
     * 类加载期固化成常量会让注入的配置不生效。
     */
    private static String fileSaveRootDir() {
        return AppConstant.getCodeOutputRootDir();
    }

    public final File saveCode(T result, Long appId){
        validateInput(result);
        String baseDirPath = buildUniqueDir(appId);
        saveFiles(result,baseDirPath);
        return new File(baseDirPath);
    }

    protected abstract void saveFiles(T result, String baseDirPath);

    protected final String buildUniqueDir(Long appId){
        if (appId == null)
        {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"应用ID不能为空");
        }
        String codeType = getCodeType().getValue();
        String uniqueDirName = StrUtil.format("{}_{}", codeType, appId);
        String dirPath = fileSaveRootDir() + File.separator + uniqueDirName;
        FileUtil.mkdir(dirPath);
        return dirPath;
    }

    protected abstract CodeGenTypeEnum getCodeType();

    protected void validateInput(T result) {
        if (result == null){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"代码结果对象不能为空");
        }
    }

    protected final void writeToFile(String dirPath, String filename, String content){
        if (StrUtil.isNotBlank(content)){
            String filePath = dirPath + File.separator + filename;
            FileUtil.writeString(content, filePath, "utf-8");
        }
    }
}
