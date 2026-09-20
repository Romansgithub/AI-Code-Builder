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
     * 文件保存根目录。
     *
     * <p>刻意不做成 {@code static final} 字段：路径由 StorageProperties 在启动时回填，
     * 若在类加载期就固化为常量，注入的配置将不生效。</p>
     */
    private static String fileSaveRootDir() {
        return AppConstant.getCodeOutputRootDir();
    }

    /**
     * 保存代码文件
     * @param result
     * @return
     */
    public final File saveCode(T result, Long appId){
        validateInput(result);
        String baseDirPath = buildUniqueDir(appId);
        saveFiles(result,baseDirPath);
        return new File(baseDirPath);
    }

    /**
     * 保存文件
     * @param result
     * @param baseDirPath
     */
    protected abstract void saveFiles(T result, String baseDirPath);

    /**
     * 构建唯一目录
     * @return
     */
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

    /**
     * 获取代码类型
     * @return
     */
    protected abstract CodeGenTypeEnum getCodeType();

    /**
     * 验证输入
     * @param result
     */
    protected void validateInput(T result) {
        if (result == null){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"代码结果对象不能为空");
        }
    }

    /**
     * 将内容写入文件
     * @param dirPath
     * @param filename
     * @param content
     */
    protected final void writeToFile(String dirPath, String filename, String content){
        if (StrUtil.isNotBlank(content)){
            String filePath = dirPath + File.separator + filename;
            FileUtil.writeString(content, filePath, "utf-8");
        }
    }
}
