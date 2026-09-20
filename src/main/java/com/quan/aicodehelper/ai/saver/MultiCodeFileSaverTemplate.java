package com.quan.aicodehelper.ai.saver;

import cn.hutool.core.util.StrUtil;
import com.quan.aicodehelper.ai.model.MultiFileCodeResult;
import com.quan.aicodehelper.exception.BusinessException;
import com.quan.aicodehelper.exception.ErrorCode;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;

public class MultiCodeFileSaverTemplate extends CodeFileSaverTemplate<MultiFileCodeResult>{
    @Override
    protected void saveFiles(MultiFileCodeResult result, String baseDirPath) {
        writeToFile(baseDirPath, "index.html", result.getHtmlCode());
        writeToFile(baseDirPath, "style.css", result.getCssCode());
        writeToFile(baseDirPath, "script.js", result.getJsCode());
    }

    @Override
    protected void validateInput(MultiFileCodeResult result) {
        super.validateInput(result);
        if (StrUtil.isBlank(result.getHtmlCode())){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"HTML内容不可为空");
        }
    }

    @Override
    protected CodeGenTypeEnum getCodeType() {
        return CodeGenTypeEnum.MULTI_FILE;
    }
}
